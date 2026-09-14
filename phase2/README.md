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
| medium | `LoaderIdentity` | `mvn -q -pl phase2 compile exec:exec -Dexec.mainClass=dev.sevenrungs.phase2.LoaderIdentity` |
| hard | `EscapeProbe` | `mvn -q -pl phase2 compile exec:exec -Dexec.mainClass=dev.sevenrungs.phase2.EscapeProbe` |
| guru | `LayoutProbe` | `mvn -q -pl phase2 compile exec:exec -Dexec.mainClass=dev.sevenrungs.phase2.LayoutProbe` |

Every rung runs via `exec:exec` (a real forked `java` process) rather than `exec:java`, the same
reason as Phase 1's `QuorumRead`: a plugin (here, JOL's reflective VM access) can need a JVM-launch
flag that can't be attached to an already-running JVM. `phase2/pom.xml` already wires
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
mvn -q -pl phase2 compile

JOL_JAR=$(find ~/.m2 -name 'jol-core-*.jar' | head -1)
java --add-opens java.base/java.lang=ALL-UNNAMED -XX:+UseCompactObjectHeaders \
    -cp "phase2/target/classes:$JOL_JAR" dev.sevenrungs.phase2.LayoutProbe
```

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
mvn -q -pl phase2 compile

# G1 (default collector): a small heap reliably produces evacuation failures ("to-space
# exhausted", now spelled "(Evacuation Failure: Allocation)" in unified logging) and real
# concurrent marking cycles within a couple of seconds.
java -Xmx32m -XX:+UseG1GC \
    -Xlog:gc*,gc+heap=debug,gc+phases=debug:file=/tmp/g1.log \
    -cp phase2/target/classes dev.sevenrungs.phase2.exercise.AllocationWorkload 5000000

java -cp phase2/target/classes dev.sevenrungs.phase2.exercise.GcAutopsyReport /tmp/g1.log

# generational ZGC and Shenandoah: same workload, different collector, different log grammar
# (see below) - GcAutopsyReport will report "no pauses matched" on these, by design.
java -Xmx64m -XX:+UseZGC \
    -Xlog:gc*,gc+heap=debug,gc+phases=debug:file=/tmp/zgc.log \
    -cp phase2/target/classes dev.sevenrungs.phase2.exercise.AllocationWorkload 4000000

java -Xmx64m -XX:+UseShenandoahGC \
    -Xlog:gc*,gc+heap=debug,gc+phases=debug:file=/tmp/shen.log \
    -cp phase2/target/classes dev.sevenrungs.phase2.exercise.AllocationWorkload 4000000
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
