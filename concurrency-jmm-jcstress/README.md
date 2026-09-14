# Phase 1 exercise — jcstress suite

Four [jcstress](https://github.com/openjdk/jcstress) tests:

- `SBTest` — store buffering, in `Plain` / `ReleaseAcquire` / `Volatile` variants. Only volatile
  forbids the interesting outcome.
- `MPTest` — message passing, same three variants. Release/acquire is already enough here,
  unlike SB — the guard field needs it, the payload field does not.
- `IRIWTest` — independent reads of independent writes, same three variants. This is the one test
  where release/acquire is *not* enough; only volatile (full sequential consistency, JLS §17.4.4)
  forbids it. This is also the test most likely to actually disagree between an x86 machine
  (multi-copy-atomic in practice) and an ARM machine (not) — see "Comparing machines" below.
- `WriterPreferringLockStressTest` — hammers `dev.sevenrungs.concurrency.WriterPreferringReadWriteLock`
  with two writers incrementing a plain `long` and one reader observing it; any outcome besides
  0, 1 or 2 would mean the lock has a real visibility bug, not a benign race.

Every `@Outcome` carries a `desc` naming the specific JLS rule that makes it ACCEPTABLE,
ACCEPTABLE_INTERESTING or FORBIDDEN — that is where the exercise's "one sentence per outcome"
requirement lives.

## Running it

This module is **not** wired into `mvn verify` — jcstress forks a JVM per test combination and a
real run takes minutes, which does not belong in an ordinary build. `verify` only compiles it.
To actually run the suite:

```
mvn -q -pl concurrency-jmm-jcstress -am package   # builds target/jcstress.jar (an executable uber-jar)
java -jar concurrency-jmm-jcstress/target/jcstress.jar
```

Add `-t <regex>` to run a subset (e.g. `-t SBTest` for just the store-buffering variants), and
`-v` for verbose per-outcome reporting. Results land in `results.jcstress.json` /
`jcstress-results.*.txt` in the working directory by default; pass `-r <name>` to control that.

## Comparing machines

The exercise's "done when" criterion is that the suite runs green on x86 *and* on an ARM machine,
and that the two disagree on at least one plain-mode outcome. Run the jar built above on both:

```
# on each machine:
java -jar jcstress.jar -t IRIWTest.Plain -v
```

x86 is multi-copy-atomic in practice, so `IRIWTest.Plain`'s `ACCEPTABLE_INTERESTING` outcome
(`1, 0, 1, 0`) will typically show a very low or zero count there. On an ARM machine (which is not
multi-copy-atomic), expect that same outcome to show up far more readily — the divergence itself
is the point: it is exactly the gap between "what the JLS allows" and "what a specific piece of
hardware bothers to forbid without help from the JVM."
