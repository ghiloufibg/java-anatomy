# Phase 1 — Concurrency internals and the Java Memory Model

Senior developers use `java.util.concurrent` correctly by convention. This phase is about being
able to say, per JLS §17.4.5, *which* happens-before edge makes a given program correct — and
therefore which edge can be removed for speed.

## Read in this order

1. JLS §17.4 end to end, then §17.4.5 again with `StoreBuffering` open. Draw the edges.
2. JEP 193's access-mode table (plain / opaque / release-acquire / volatile).
3. Doug Lea's [AQS paper](https://gee.cs.oswego.edu/dl/papers/aqs.pdf), then
   `AbstractQueuedSynchronizer.java` in `src/java.base`: the CLH variant, `acquire()`'s
   spin-then-park loop, and the `PROPAGATE` status.
4. [JEP 444](https://openjdk.org/jeps/444) §"Scheduling" and §"Pinning", then
   [JEP 491](https://openjdk.org/jeps/491) for how monitors stopped pinning.
5. [JEP 505](https://openjdk.org/jeps/505) (Structured Concurrency) and
   [JEP 506](https://openjdk.org/jeps/506) (Scoped Values) together.

## The three rungs

| Difficulty | Class | Run it |
|---|---|---|
| medium | `StoreBuffering` | `mvn -q -pl phase1 compile exec:exec -Dexec.mainClass=dev.sevenrungs.phase1.StoreBuffering` |
| medium | `StoreBuffering` (volatile) | `... -Dexec.programArgs=volatile` |
| hard | `PhaseGate` | `mvn -q -pl phase1 compile exec:exec -Dexec.mainClass=dev.sevenrungs.phase1.PhaseGate` |
| guru | `QuorumRead` | `mvn -q -pl phase1 compile exec:exec -Dexec.mainClass=dev.sevenrungs.phase1.QuorumRead` |
| exercise | `PinDemo` | `mvn -q -pl phase1 compile exec:exec -Dexec.mainClass=dev.sevenrungs.phase1.PinDemo` |

`QuorumRead` needs `--enable-preview` (JEP 505 is still preview in JDK 25). Every rung here runs
via the `exec:exec` goal rather than `exec:java`: `exec:java` runs in-process and the JVM only
honors `--enable-preview` at its own launch, so it can never load a preview-compiled class no
matter how it's configured. `exec:exec` forks a real `java` process instead, with the flag
already wired into the module's `pom.xml` — no extra flags needed on the command line, and this
applies uniformly to every rung (harmless for the ones that don't use preview features).

`StoreBuffering`'s default demo runs 2,000,000 trials, which spawns two platform threads per
trial — on a fast native machine this finishes in seconds, but under heavy sandboxing or
virtualization (where thread creation is expensive) it can take much longer. The JUnit tests use
far fewer trials and finish quickly regardless; if the full demo feels stuck, that's environment
thread-creation overhead, not a hang.

## The exercise

> Build a jcstress project with the SB, MP and IRIW litmus tests, each in three variants: plain,
> release/acquire, volatile. For every forbidden or allowed outcome, write one sentence citing
> the JLS rule that decides it. Then implement a writer-preferring read/write lock on AQS
> (exclusive and shared modes in one class) and prove it with jcstress and a jtreg-style stress
> test. Port the lock's clients to virtual threads. Find the one place it still pins a carrier
> before JDK 24 and explain why JEP 491 fixed it.

What's here for it:

- **`WriterPreferringReadWriteLock`** — one `AbstractQueuedSynchronizer` subclass with both
  exclusive (write) and shared (read) modes, where a writer that starts waiting blocks *new*
  readers immediately, even while the lock is still free or held only by readers. See
  `WriterPreferringReadWriteLockTest` for a barrier/thread-state-driven proof of mutual exclusion,
  concurrent reads, and the preference ordering itself.
- **`PinDemo`** — isolates the exact pattern that pinned a virtual thread's carrier before JDK
  24: entering `synchronized` and then blocking while still holding the monitor.
  `WriterPreferringReadWriteLock` itself never does this (it blocks only via `LockSupport.park`
  through AQS, which never pinned). `PinDemoTest` subscribes to the JVM's own
  `jdk.VirtualThreadPinned` JFR event and proves zero fire on JDK 25 — the JEP 491 guarantee,
  verified rather than assumed.
- **`phase1-jcstress`** ([README](../phase1-jcstress/README.md)) — the SB/MP/IRIW suite (one
  sentence per outcome, in each `@Outcome(desc = ...)`) and a stress test for the lock above.

## Tests

`mvn -q -pl phase1 test` runs the JUnit 5 suite. `StoreBufferingTest` only asserts the one thing
JLS §17.4 actually guarantees (the volatile variant never reorders); the opaque/plain reordering
itself is a hardware- and JIT-dependent demonstration best read from `StoreBuffering`'s own
console output, not asserted in CI.
