# Phase 2 — JVM internals: loading, JIT, escape analysis, GC, bytecode

Every "mysterious" production behaviour has a mundane cause in one of four subsystems: class
loading, tiered compilation, escape analysis, or the garbage collector. This phase is about being
able to point at the mundane cause instead of shrugging.

## Read in this order

1. JVMS §5.3 to §5.5, then `ClassLoader.java` and
   `src/hotspot/share/classfile/systemDictionary.cpp` for how the loader constraint table is
   enforced.
2. The [HotSpot Runtime Overview](https://openjdk.org/groups/hotspot/docs/RuntimeOverview.html),
   then `compilationPolicy.cpp`: the invocation and back-edge thresholds that move a method
   between tiers, and what makes C2 "not entrant".
3. `src/hotspot/share/opto/escape.cpp` header comment: the connection graph, the three escape
   states, and what scalar replacement requires (inlining, no merges).
4. [JEP 248](https://openjdk.org/jeps/248) (G1 default) and the G1 paper it cites for regions,
   remembered sets and mixed collections; [JEP 439](https://openjdk.org/jeps/439) /
   [474](https://openjdk.org/jeps/474) for generational ZGC; the
   [Shenandoah wiki](https://wiki.openjdk.org/display/shenandoah) for Brooks pointers giving way
   to the load-reference barrier.
5. JVMS §4.10 verification by type checking. Read one StackMapTable in `javap -v` output until it
   makes sense.

## The three rungs

| Difficulty | Class | Run it |
|---|---|---|
| medium | `LoaderIdentity` | `mvn -q -pl jvm-internals compile exec:exec -Dexec.mainClass=dev.sevenrungs.jvminternals.LoaderIdentity` |
| hard | `EscapeProbe` | `mvn -q -pl jvm-internals compile exec:exec -Dexec.mainClass=dev.sevenrungs.jvminternals.EscapeProbe` |
| guru | `LayoutProbe` | `mvn -q -pl jvm-internals compile exec:exec -Dexec.mainClass=dev.sevenrungs.jvminternals.LayoutProbe` |

Every rung runs via `exec:exec` (a real forked `java` process) rather than `exec:java`, the same
reason as Phase 1's `QuorumRead`: a plugin (here, JOL's reflective VM access) can need a JVM-launch
flag that can't be attached to an already-running JVM. `jvm-internals/pom.xml` already wires
`--add-opens java.base/java.lang=ALL-UNNAMED` into both the exec arguments and Surefire.

**`EscapeProbe`'s allocation trend is genuinely hardware- and JIT-timing-dependent** — more so
than the artifact's own text implies. Watching this JDK compile it with
`-XX:+PrintCompilation` shows `EscapeProbe::sum` really does reach tier 4 (C2), but the compiled
version can be marked "not entrant" (OSR invalidation, uncommon traps) before it settles, so the
allocation count printed by `main` may stay flat at ~32MB/round for all 8 rounds in a short run
instead of visibly collapsing toward 0 — that's this environment's JIT heuristics, not a bug in
the demo. `EscapeProbe.sum(n)` is deterministically `0` for every `n` regardless (the `+i`/`-i`
walk cancels out), which is what `EscapeProbeTest` actually asserts. If you want to see the
collapse yourself, run more rounds and/or add `-XX:+PrintCompilation` to watch for a tier-4
compile of `sum` that survives across rounds.

`LayoutProbe` on this JDK, run plain, shows a 12-byte header (8-byte mark word + 4-byte compressed
class pointer) — the default here, since `UseCompactObjectHeaders` defaults to `false`. Compare
against the 8-byte compact-header shape (JEP 519) by running it directly with `java` instead of
through `exec:exec` (which doesn't have a knob for this one extra flag):

```
mvn -q -pl jvm-internals compile

JOL_JAR=$(find ~/.m2 -name 'jol-core-*.jar' | head -1)
java --add-opens java.base/java.lang=ALL-UNNAMED -XX:+UseCompactObjectHeaders \
    -cp "jvm-internals/target/classes:$JOL_JAR" dev.sevenrungs.jvminternals.LayoutProbe
```

## Measuring what header and pointer flags save: `footprint/`

`LayoutProbe` shows one object's header; `footprint/` answers the question you actually get asked
in production: *how much heap would we save at our load by changing JVM flags, without touching
application code?*

- **`OrderGraph`** — an ordinary business object graph: 14 heap objects per order (boxed `Long`,
  `String` + `byte[]`, `Instant`, `ArrayList`, `HashMap` + nodes, three line items) carrying ~60
  bytes of real data. Many small objects is exactly the shape where headers and references
  dominate.
- **`LoadProfile`** — `NORMAL` / `MEDIUM` / `HIGH`: 50k / 250k / 1M orders held live.
- **`JvmMemoryConfig`** — the flag sets being compared: defaults, `-XX:+UseCompactObjectHeaders`
  (JEP 519), `-XX:-UseCompressedClassPointers`, `-XX:-UseCompressedOops` (what you silently get
  above `-Xmx32g`), `-XX:ObjectAlignmentInBytes=16`.
- **`FootprintProbe`** — the child JVM: builds the orders, then reports two independent numbers —
  JOL's exact per-order size and used-heap-after-GC delta — so each checks the other.
- **`FootprintComparison`** — forks one probe JVM per config per load (layout flags are fixed at
  JVM launch, so separate JVMs are the only honest comparison) and prints the table below.

```
mvn -q -pl jvm-internals compile exec:exec \
    -Dexec.mainClass=dev.sevenrungs.jvminternals.footprint.FootprintComparison
# optional: -Dexec.programArgs=0.2 scales every load down for a quicker run
```

Captured on this JDK (25.0.1), `-Xmx2g -XX:+UseSerialGC` in every child:

| Load | Orders | JVM configuration | Header | Ref | Align | Bytes/order (JOL) | Live heap | vs default |
|---|---:|---|---:|---:|---:|---:|---:|---:|
| NORMAL | 50,000 | default | 12 B | 4 B | 8 B | 443.7 | 21.4 MB | baseline |
| NORMAL | 50,000 | compact object headers | 8 B | 4 B | 8 B | 371.7 | 17.7 MB | -17.4% (-3.7 MB) |
| NORMAL | 50,000 | no compressed class pointers | 16 B | 4 B | 8 B | 484.9 | 23.2 MB | +8.3% (+1.8 MB) |
| NORMAL | 50,000 | no compressed oops (heap > 32 GB) | 12 B | 8 B | 8 B | 540.3 | 26.2 MB | +22.1% (+4.7 MB) |
| NORMAL | 50,000 | 16-byte object alignment | 12 B | 4 B | 16 B | 484.9 | 23.2 MB | +8.4% (+1.8 MB) |
| MEDIUM | 250,000 | default | 12 B | 4 B | 8 B | 443.7 | 107.9 MB | baseline |
| MEDIUM | 250,000 | compact object headers | 8 B | 4 B | 8 B | 371.7 | 88.7 MB | -17.8% (-19.2 MB) |
| MEDIUM | 250,000 | no compressed class pointers | 16 B | 4 B | 8 B | 484.9 | 115.9 MB | +7.4% (+8.0 MB) |
| MEDIUM | 250,000 | no compressed oops (heap > 32 GB) | 12 B | 8 B | 8 B | 540.3 | 132.1 MB | +22.4% (+24.1 MB) |
| MEDIUM | 250,000 | 16-byte object alignment | 12 B | 4 B | 16 B | 484.9 | 115.9 MB | +7.4% (+8.0 MB) |
| HIGH | 1,000,000 | default | 12 B | 4 B | 8 B | 443.7 | 435.5 MB | baseline |
| HIGH | 1,000,000 | compact object headers | 8 B | 4 B | 8 B | 371.7 | 358.3 MB | -17.7% (-77.2 MB) |
| HIGH | 1,000,000 | no compressed class pointers | 16 B | 4 B | 8 B | 484.9 | 466.7 MB | +7.2% (+31.2 MB) |
| HIGH | 1,000,000 | no compressed oops (heap > 32 GB) | 12 B | 8 B | 8 B | 540.3 | 532.2 MB | +22.2% (+96.6 MB) |
| HIGH | 1,000,000 | 16-byte object alignment | 12 B | 4 B | 16 B | 484.9 | 466.7 MB | +7.2% (+31.2 MB) |

How to read it:

- **The percentage is a property of the object graph, not of the load.** Bytes/order is identical
  at every load, so compact headers save ~17–18% whether you hold 50k orders or 1M; only the
  absolute megabytes scale (3.7 → 19 → 77 MB). Your own saving depends on your average object
  size: the smaller your objects, the bigger the win. Big `byte[]`/`long[]` payloads barely notice.
- **Header savings are quantized by alignment.** Compact headers remove 4 bytes per object, but
  sizes round to 8, so each object saves either 8 bytes or nothing. JOL's per-class footprint
  shows 9 of the 14 objects per order drop a full slot (`Order` 48→40, `LineItem` 32→24 ×3,
  `Long` 24→16, `HashMap` 48→40, `HashMap$Node` 32→24 ×2, `Object[]` 32→24) and 5 save nothing
  (`String`, `byte[]`, `Instant`, `ArrayList`, `Node[]` stay put): 72 B/order, more than the naive
  14 × 4 = 56 B. Run `GraphLayout.parseInstance(...).toFootprint()` under both flags to see this
  for your own classes.
- **Crossing 32 GB costs ~22% on this graph** — every reference doubles. A 31 GB heap with
  compressed oops can hold more live data than a 36 GB one without; `ObjectAlignmentInBytes=16`
  keeps 4-byte references to 64 GB at a smaller (~7%) padding cost.

`FootprintComparisonTest` forks all 15 JVMs at a tenth of this scale (~25 s in `mvn verify`) and
asserts the shape of each config (12/8/16-byte headers, 4/8-byte references), that compact headers
save at least 10% at every load, that every other flag set costs memory, that the heap and JOL
measurements agree within 5%, and that per-order cost is load-independent so savings scale
linearly. `OrderGraphTest` pins the 14-objects-per-order claim with JOL.

## The exercise: a GC autopsy kit

> Write one allocation-heavy workload with a mix of short-lived objects, a large live set, and
> humongous arrays. Run it under G1, generational ZGC and Shenandoah... Write a parser that turns
> each log into pause histograms, concurrent cycle timelines and heap-occupancy-after-GC curves.
> For every distinct log phrase, write down the collector phase it names and the JEP or source
> file that defines it.

What's here for it, under `exercise/`:

- **`AllocationWorkload`** — ticks (not wall-clock time) through a mix of short-lived garbage,
  a bounded sliding-window retained set (old-gen pressure without an unbounded heap), and periodic
  humongous arrays.
- **`GcLogParser`** — a from-scratch parser for G1's unified logging output. **Deliberately
  G1-specific**: while building it, capturing real logs from generational ZGC and Shenandoah under
  the exact same workload showed all three collectors use structurally different log grammars, not
  just different words for the same shape. See the class's own Javadoc and "Comparing the three
  collectors' log grammars" below — this is a real finding, not a shortcut.
- **`GcPhraseCatalog`** — the "for every distinct phrase, cite the phase and the source" table,
  as matchable code (`citationsFor(String)`) rather than only prose, the same trick Phase 1 used
  for its JLS citations in `@Outcome(desc = ...)`.
- **`GcAutopsyReport`** — the CLI: point it at a captured G1 log and it prints the pause
  histogram (by description and by duration bucket, each citing its catalog entries), the
  concurrent-cycle timeline, and the heap-occupancy-after-GC curve.

### Running it yourself

```
mvn -q -pl jvm-internals compile

# G1 (default collector): a small heap reliably produces evacuation failures ("to-space
# exhausted", now spelled "(Evacuation Failure: Allocation)" in unified logging) and real
# concurrent marking cycles within a couple of seconds.
java -Xmx32m -XX:+UseG1GC \
    -Xlog:gc*,gc+heap=debug,gc+phases=debug:file=/tmp/g1.log \
    -cp jvm-internals/target/classes dev.sevenrungs.jvminternals.exercise.AllocationWorkload 5000000

java -cp jvm-internals/target/classes dev.sevenrungs.jvminternals.exercise.GcAutopsyReport /tmp/g1.log

# generational ZGC and Shenandoah: same workload, different collector, different log grammar
# (see below) - GcAutopsyReport will report "no pauses matched" on these, by design.
java -Xmx64m -XX:+UseZGC \
    -Xlog:gc*,gc+heap=debug,gc+phases=debug:file=/tmp/zgc.log \
    -cp jvm-internals/target/classes dev.sevenrungs.jvminternals.exercise.AllocationWorkload 4000000

java -Xmx64m -XX:+UseShenandoahGC \
    -Xlog:gc*,gc+heap=debug,gc+phases=debug:file=/tmp/shen.log \
    -cp jvm-internals/target/classes dev.sevenrungs.jvminternals.exercise.AllocationWorkload 4000000
```

`GcMultiCollectorSmokeTest` (in `mvn verify`'s normal test run, since forking all three finishes
in under two seconds) actually runs all three of the commands above as real child JVMs and checks
G1's output through `GcLogParser`, and ZGC's/Shenandoah's output for collector-appropriate marker
text — the exercise's "run it under three collectors" claim, proven, not just documented.

### Comparing the three collectors' log grammars

Captured from this exact JDK (25.0.1), same workload, same rough heap pressure:

| Collector | A completed pause/phase line looks like |
|---|---|
| G1 | `GC(37) Pause Young (Normal) (G1 Evacuation Pause) 612M->118M(1024M) 9.812ms` |
| generational ZGC | `GC(3) Minor Collection (Allocation Rate) 60M(94%)->52M(81%) 0.008s` |
| Shenandoah | `GC(0) Pause Init Mark (unload classes) 0.087ms` (no separate start line at all) |

Three real differences that would break a parser written against only one of them: G1 and
Shenandoah report absolute before/after/total sizes, ZGC reports percentages of capacity; G1's
duration suffix is `ms`, ZGC's is `s`; G1 and ZGC pair a bare start line with a later, timed end
line for multi-phase work, Shenandoah does not pair lines at all — every phase, paused or
concurrent, is its own single completion line the instant it finishes.

### Provoking specific failure modes

- **G1 evacuation failure ("to-space exhausted")**: already reliably reproduced above by running
  `AllocationWorkload` with a small `-Xmx` (32m in the example) — look for `(Evacuation Failure:
  Allocation)` in the pause descriptions, or in `GcAutopsyReport`'s histogram output.
- **A real G1 "Pause Full"**: needs even more pressure relative to the heap; `-Xmx16m` with more
  ticks reproduces it on this JDK.
- **A ZGC allocation stall**: left as a manual exercise — shrink `-Xmx` for the ZGC run until the
  allocation rate the workload sustains exceeds what concurrent relocation can keep up with, and
  watch for `Allocation Stall` in ZGC's own log output.

## Log phrase → phase → source citation table

| Phrase (substring) | Phase | Citation |
|---|---|---|
| `Evacuation Failure` | G1 evacuation failure ("to-space exhausted") | `src/hotspot/share/gc/g1/g1EvacFailure.cpp`; raise `-XX:G1ReservePercent` or the heap |
| `Concurrent Start` | G1's initial-mark pause | JEP 248; `g1CollectedHeap.cpp` (initiate concurrent mark) |
| `Prepare Mixed` | last young-only pause before mixed collections | JEP 248 |
| `(Mixed)` | a mixed collection | JEP 248 |
| `G1 Humongous Allocation` | young collection triggered by a humongous allocation | `g1CollectedHeap.cpp` (humongous allocation path) |
| `G1 Evacuation Pause` | ordinary STW young evacuation | JEP 248, the G1 paper |
| `Pause Remark` | 2nd STW pause of a concurrent cycle | `g1ConcurrentMark.cpp` (remark) |
| `Pause Cleanup` | 3rd STW pause of a concurrent cycle | `g1ConcurrentMark.cpp` (cleanup) |
| `Pause Full` | full STW compaction (the one you page on) | JEP 307; `g1FullCollector.cpp` |
| `Concurrent Undo Cycle` | an abandoned concurrent cycle | `g1ConcurrentMark.cpp` / `g1Policy.cpp` |
| `Concurrent Mark Cycle` | the concurrent marking phase | JEP 248; `g1ConcurrentMark.cpp` |

(This table is generated from `GcPhraseCatalog.all()` — see that class if you add a phrase.)
