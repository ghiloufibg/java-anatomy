# Phase 5 exercise — the same allocation profiler, three ways

One `java.lang.instrument` agent, `AllocationAgent`, that counts every allocation site
(`NEW`/`ANEWARRAY`/`NEWARRAY`/`MULTIANEWARRAY`) in a target class — implemented three separate
times, once per bytecode API: the JDK's own ClassFile API (JEP 484), ASM, and Byte Buddy. Same
technique in all three: insert a call to `AllocationRecorder.record(site)` immediately *before*
each allocation opcode. That ordering needs no stack-shape bookkeeping — a static `void` call with
a constant-string argument never touches the pre-existing operand stack — which is exactly what
makes the same idea implementable cleanly, and comparably, in all three APIs.

```
Instrumenter (interface: byte[] instrument(String ownerInternalName, byte[] original))
  ├── ClassFileApiInstrumenter   (java.lang.classfile)
  ├── AsmInstrumenter            (org.objectweb.asm)
  └── ByteBuddyInstrumenter      (net.bytebuddy)
```

`Workload` is the shared instrumentation target (one method per allocation kind).
`AllocationAgent` selects an implementation from its `agentArgs` (`<impl>:<prefix>`, e.g.
`asm:dev.sevenrungs.compilertooling.profiler.Workload`) and prints a sorted report on JVM exit via
a shutdown hook. `AllocationBenchmarks` is a JMH class comparing baseline vs. each instrumented
variant's per-call overhead.

## Two real dependency-version corrections, found before writing any instrumentation code

- **ASM 9.7.1** — a natural default choice — throws `Unsupported class file major version 69`
  reading *any* class this project compiles (JDK 25). **ASM 9.8** reads and writes JDK 25 class
  files correctly; confirmed empirically before picking it.
- **Byte Buddy 1.15.10** refuses JDK 25 outright unless `-Dnet.bytebuddy.experimental=true` is set
  on the JVM running it. **Byte Buddy 1.17.5** supports JDK 25 natively, no experimental flag
  needed; confirmed empirically the same way.

Both are pinned via `${asm.version}` / `${bytebuddy.version}` in the root `pom.xml`.

## What each API made easy vs. painful

- **ClassFile API** — the most direct: `ClassBuilder.transformMethod` plus a `CodeTransform`
  pattern-matching on instruction types (`NewObjectInstruction`, `NewReferenceArrayInstruction`,
  `NewPrimitiveArrayInstruction`, `NewMultiArrayInstruction`) reads almost like a specification of
  what counts as an allocation. Stack maps and `max_stack` are regenerated for you — nothing to
  compute by hand.
- **ASM** — one level lower: overriding `visitTypeInsn`/`visitIntInsn`/`visitMultiANewArrayInsn`
  on a `MethodVisitor` is direct and fast, but `NEWARRAY`'s primitive-type argument is a raw `int`
  opcode operand (`Opcodes.T_INT` etc.) that has to be switched into a name by hand to match the
  other two implementations' `TypeKind.INT.name()` string. Explicit `ClassWriter(COMPUTE_FRAMES |
  COMPUTE_MAXS)` is required, and it *is* required — see below.
- **Byte Buddy** — its high-level `Advice` API only instruments method entry/exit, not arbitrary
  opcodes like `NEW`; reaching per-instruction detail needs its lower-level `AsmVisitorWrapper`,
  which hands over a real ASM `ClassVisitor` — from `net.bytebuddy.jar.asm`, Byte Buddy's own
  **shaded, relocated copy** of ASM. `net.bytebuddy.jar.asm.ClassVisitor` and
  `org.objectweb.asm.ClassVisitor` are unrelated types at the bytecode level, so
  `ByteBuddyInstrumenter` cannot share a single line of visitor code with `AsmInstrumenter` even
  though both do the exact same thing — a real, worth-noting finding for anyone hoping to reuse
  ASM tooling underneath a Byte Buddy pipeline.

## A real bug this project shipped, found once tests forked real child JVMs

Early versions of this module's tests defined an instrumenter's output class in-process — a fresh
`ClassLoader` plus `defineClass` — to check its behavior without a full agent attach. That hit a
reproducible `VerifyError`:

```
Exception in thread "main" java.lang.VerifyError: Operand stack overflow
  Location: Workload.allocatePrimitiveArray(I)[I @1: ldc
  Reason: Exceeded max stack size.
```

For a while this looked like it depended on the surrounding JUnit execution machinery: it
persisted across renaming the defined class, forcing eager linking via
`Class.forName(name, true, loader)`, disabling Surefire's `useManifestOnlyJar` fork mode, and even
swapping Surefire out entirely for the bare JUnit Platform Launcher API — all consistent with "the
class is fine, something about *this* execution environment isn't."

Rather than keep chasing that theory, the tests were redesigned to fork a real, separate child JVM
per implementation (`AgentProcessSupport`) — both a more reliable check and a more faithful one:
it exercises the actual `-javaagent` attach path a user would use, not an ad-hoc in-process
approximation. Once every check ran that way, **the real cause surfaced immediately**: it wasn't
JUnit, or in-process definition, or any of the above at all. It was `ByteBuddyInstrumenter`,
deterministically, in *every* environment including a genuinely fresh `java` process —
`ClassFileApiInstrumenter` and `AsmInstrumenter` never had this problem, in-process or otherwise.

The actual bug: `AsmVisitorWrapper.AbstractBase`'s default `mergeWriter(int flags)` leaves Byte
Buddy's underlying `ClassWriter` flags untouched, so it keeps reusing each method's *original*
`max_stack`. Every `ldc` + `invokestatic` this project's wrapper inserts before an allocation
opcode needs two extra stack slots the original bytecode never needed — without requesting
`ClassWriter.COMPUTE_MAXS`, the written class file understates its own `max_stack` and fails
verification. The fix is one method:

```java
@Override
public int mergeWriter(int flags) {
  return flags | ClassWriter.COMPUTE_MAXS;
}
```

added to `ByteBuddyInstrumenter`'s `CountingVisitorWrapper`. This is the exercise's own point
sharpened by a real accident: "run every output class through a verifier" isn't a formality — one
of these three implementations genuinely failed it, for a genuinely findable reason, and the
fastest way to it was proving the failure had nothing to do with the test harness first.

The child-JVM test design stays regardless of what first motivated it — it remains the more
faithful way to test a `java.lang.instrument` agent's real usage path than any in-process
`defineClass()` trick.

## Run it for real

```
mvn -q -pl compiler-tooling-profilers -am install -DskipTests   # builds profilers.jar
```

As a real agent, all three implementations, against `Workload`:

```
java -javaagent:profilers.jar=classfile:dev.sevenrungs.compilertooling.profiler.Workload -cp ... WorkloadRunnerFixture
java -javaagent:profilers.jar=asm:dev.sevenrungs.compilertooling.profiler.Workload       -cp ... WorkloadRunnerFixture
java -javaagent:profilers.jar=bytebuddy:dev.sevenrungs.compilertooling.profiler.Workload -cp ... WorkloadRunnerFixture
```

All three report identical counts (real captured output, one line per site):

```
workload complete
dev/sevenrungs/compilertooling/profiler/Workload.allocateMultiArray:[[I = 1
dev/sevenrungs/compilertooling/profiler/Workload.allocateObject:java/lang/Object = 1
dev/sevenrungs/compilertooling/profiler/Workload.allocatePrimitiveArray:INT[] = 1
dev/sevenrungs/compilertooling/profiler/Workload.allocateReferenceArray:java/lang/String[] = 1
```

And as a runnable JMH benchmark (the same jar carries both `Premain-Class` and `Main-Class` — a
combination real APM agent jars often use):

```
java -jar profilers.jar -f 1 -wi 2 -i 2
```

Real numbers from this JDK/sandbox (`-f 1 -wi 2 -i 3 -w 500ms -r 500ms`, x86_64, 2026-09-14):

```
Benchmark                          Mode  Cnt   Score    Error  Units
AllocationBenchmarks.baseline      avgt    3   8.362 ±  8.948  ns/op
AllocationBenchmarks.asm           avgt    3  20.455 ±  9.794  ns/op
AllocationBenchmarks.classFileApi  avgt    3  20.900 ± 20.038  ns/op
AllocationBenchmarks.byteBuddy     avgt    3  22.571 ± 26.104  ns/op
```

All three instrumented variants land in the same rough band (~2.5x the baseline for this tiny,
four-allocation-site workload) — unsurprising, since all three insert the identical extra
`ldc`+`invokestatic` pair per site; the differences between them are within this short profile's
own error bars, not a meaningful ranking. A longer profile (`-wi 5 -i 10`, JMH's own default) would
be needed before treating any ordering among the three as real.

## Putting the agent to work: which code would a JVM flag save memory in? (`attribution/`)

Phase 2's `FootprintEstimator` (`jvm-internals/.../footprint/estimate/`) predicts, class by
class, how a live heap changes under each JVM flag set (compact object headers, no compressed
oops, and so on). `attribution/` joins that prediction with this agent's allocation sites, so
the answer names code, not just classes:

- **`AllocationSites`**: parses the report `AllocationAgent` prints at exit
  (`owner.method:type = count`). It maps the agent's bytecode-level type labels (`byte[]`,
  `java/lang/Object[]`, `[I[]`, `[[I`) to histogram names (`[B`, `[Ljava.lang.Object;`, `[[I`).
- **`AllocationAttribution`**: for each class whose footprint changes, it gives the application
  sites their share, split by allocation count. The share is `min(1, seen allocations / live
  instances)`, and the rest is reported as **allocated where the agent doesn't look**. The parts
  always add up to the estimate exactly, rounding included.
- **`SavingsAttributionReport`**: the CLI. With no arguments, it runs Phase 2's `HistogramProbe`
  under the real agent (asm, instrumenting `OrderGraph` only) and prints the tables below.
- **`AgentJar`**: builds the `-javaagent` jar from the compiled classes. It moved out of the
  tests' `AgentProcessSupport`, which now delegates to it, so the CLI can use it too.

```
mvn -q install -DskipTests -pl jvm-internals -am      # once: puts jvm-internals in ~/.m2
mvn -q -pl compiler-tooling-profilers compile exec:exec \
    -Dexec.mainClass=dev.sevenrungs.compilertooling.profiler.attribution.SavingsAttributionReport
```

(`-am` can't be combined with `exec:exec` here: it would try to run the main class in the parent
POM and in `jvm-internals` too.)

Against a real application: run it under the agent (`-javaagent:profilers.jar=asm:com.acme.`,
report on stdout at exit) and capture `jcmd <pid> GC.class_histogram` while it runs. Then pass
both files: `SavingsAttributionReport app.histo agent.txt DEFAULT path/to/app.jar`.

Captured on JDK 25.0.1, 250k orders:

| Target configuration | Total change | In your code | Allocated elsewhere |
|---|---:|---:|---:|
| compact object headers | -18.1 MB | -9.5 MB (53%) | -8.6 MB |
| no compressed class pointers | +8.6 MB | +1.9 MB (22%) | +6.7 MB |
| no compressed oops (heap > 32 GB) | +22.9 MB | +11.4 MB (50%) | +11.4 MB |
| 16-byte object alignment | +10.5 MB | +1.9 MB (18%) | +8.6 MB |

Compact headers, by site: `OrderGraph.order` allocating `LineItem` (750,000, −5.7 MB), `HashMap`
(250,000, −1.9 MB) and `Order` (250,000, −1.9 MB). Allocated where the agent doesn't look:

- `HashMap$Node` (−3.8 MB), made inside `HashMap.put`;
- `Long` (−1.9 MB), boxed inside `Long.valueOf`;
- the `byte[]`, `Object[]` and `HashMap$Node[]` behind `String`, `ArrayList` and `HashMap`
  (−1.0 MB each).

**The finding: about half the saving is in objects the JDK allocates on your code's behalf.**
`AllocationAgent` only sees `new` instructions in the classes it instruments, and it skips
`<init>`/`<clinit>`. So a `HashMap$Node` your `map.put` causes, or the `Object[]` inside the
`ArrayList` you created, appears with 0 allocations seen. The attribution reports that instead of
hiding it. Crediting those to the calling line would need stack traces, for instance from JFR's
`jdk.ObjectAllocationSample`, which this agent doesn't collect.

The one assumption: the agent counts **allocations** and the histogram counts **survivors**, so
the attribution assumes a class's objects survive in the same proportion whichever site made
them. A site whose objects all die young can be credited with savings that belong to a
longer-lived site of the same class.

Tests:

- `SavingsAttributionReportTest` (about 7 child JVMs, ~12 s) runs the full chain under the real
  agent. It checks that:
  - the agent counted exactly `N` `Order`s, `3N` `LineItem`s and `N` `HashMap`s;
  - for every configuration, attributed + unattributed equals the estimated change;
  - `order()` is credited exactly −8 B × 3N for `LineItem` under compact headers;
  - `HashMap$Node` and `Long` show up as unattributed with 0 allocations seen, not lost;
  - the attributed share of the compact-header saving is about half.
- `AllocationAttributionTest` covers the arithmetic without a JVM: fully seen, never seen and
  partly seen classes, more allocations than survivors, rounding, unchanged classes.
- `AllocationSitesTest` covers report parsing and every type-label mapping.

This module now depends on `jvm-internals`: build it from the root, or with `-am` when using
`-pl` for `verify`. The dependency and JOL are excluded from the shaded `profilers.jar`, so the agent jar is
unchanged.

## Testing strategy

- `AllocationAgentTest` / `InstrumenterAgreementTest` / `AllocationBenchmarkSmokeTest` all fork
  real child JVMs (see `AgentProcessSupport`'s Javadoc) rather than defining instrumented classes
  in-process — both for the reliability reason above and because it is what these classes are
  actually meant to run under.
- A clean exit from a forked run *is* the JVM verifier's pass/fail signal (JVMS §4.10): a
  `VerifyError` on class definition aborts the child's startup outright, so there's no separate
  "run it through a verifier" step needed.
- None of this is wired into `mvn verify`'s critical path beyond these fast checks — a real JMH
  run takes minutes, same reasoning as every other benchmark module in this project.
