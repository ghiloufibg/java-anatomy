# Seven Rungs to the JVM

A phase-by-phase, run-it-then-break-it roadmap for going from "writes correct, idiomatic Java"
to being able to explain *why* the JVM runs the way it does — reading the JLS, the JVMS, JEPs and
the HotSpot source as primary sources, not tutorials.

The whole roadmap has seven phases; this repository currently implements:

- **Phase 1 — Concurrency internals and the Java Memory Model** (`phase1`, `phase1-jcstress`)

Phases 2 through 7 (JVM internals, FFM/native interop, language evolution, compiler/tooling
craft, production JVM engineering, and contributing to OpenJDK) are not built yet; each will land
as its own module(s) the same way Phase 1 did.

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

See [`phase1/README.md`](phase1/README.md) for the three graded examples (store-buffering litmus
test, an AbstractQueuedSynchronizer-based phase gate, structured concurrency + scoped values) and
[`phase1-jcstress/README.md`](phase1-jcstress/README.md) for the phase's exercise: a jcstress
suite plus a hand-rolled writer-preferring read/write lock.
