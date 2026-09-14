# Seven Rungs to the JVM

A phase-by-phase, run-it-then-break-it roadmap for going from "writes correct, idiomatic Java"
to being able to explain *why* the JVM runs the way it does — reading the JLS, the JVMS, JEPs and
the HotSpot source as primary sources, not tutorials.

The whole roadmap has seven phases; this repository currently implements:

- **Phase 1 — Concurrency internals and the Java Memory Model** (`concurrency-jmm`, `concurrency-jmm-jcstress`)
- **Phase 2 — JVM internals: loading, JIT, escape analysis, GC, bytecode** (`jvm-internals`)
- **Phase 3 — The Foreign Function & Memory API and native interop** (`native-interop`, `native-interop-bench`)

Phases 4 through 7 (language evolution, compiler/tooling craft, production JVM engineering, and
contributing to OpenJDK) are not built yet; each will land as its own module(s) the same way
Phases 1 through 3 did.

## Toolchain

One Maven reactor, one module per phase, Google Java Format enforced (not requested) via
Spotless:

```
mvn -q spotless:apply   # rewrite every file to Google Java Style
mvn -q verify            # compile every module, re-check formatting, run the fast test suites
```

An unformatted file fails `verify` the same way a failing test would — run `spotless:apply`
first if that happens.

## Phase 1 — Concurrency and the JMM

See [`concurrency-jmm/README.md`](concurrency-jmm/README.md) for the three graded examples (store-buffering litmus
test, an AbstractQueuedSynchronizer-based phase gate, structured concurrency + scoped values) and
[`concurrency-jmm-jcstress/README.md`](concurrency-jmm-jcstress/README.md) for the phase's exercise: a jcstress
suite plus a hand-rolled writer-preferring read/write lock.

## Phase 2 — JVM internals

See [`jvm-internals/README.md`](jvm-internals/README.md) for the three graded examples (class-loader identity,
watching C2's escape analysis delete an allocation, JOL object-layout/header inspection) and the
phase's exercise: a "GC autopsy kit" — an allocation-heavy workload run under G1, generational ZGC
and Shenandoah, and a from-scratch parser turning raw `-Xlog:gc*` output into pause histograms,
concurrent-cycle timelines and heap-occupancy curves.

## Phase 3 — FFM and native interop

See [`native-interop/README.md`](native-interop/README.md) for the three graded examples (a libc downcall, a
struct-and-upcall `qsort()` binding, a cross-process ring buffer on a mapped file) and
[`native-interop-bench/README.md`](native-interop-bench/README.md) for the phase's exercise: a hand-written zlib
wrapper benchmarked against a real JNI baseline with JMH — including a factual correction, found
while building it, to what the FFM API's `Linker.Option` actually supports on this JDK.
