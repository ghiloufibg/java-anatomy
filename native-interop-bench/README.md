# Phase 3 exercise (JMH half) — FFM vs. a real JNI baseline

Compares three ways of getting a Java `byte[]` compressed by native zlib:

| Path | How it gets the bytes to native code |
|---|---|
| `ffmZlibCodec` | [`ZlibCodec`](../native-interop/README.md) (FFM): copies into an off-heap `MemorySegment`, calls, copies the result back |
| `jniCriticalZeroCopy` | JNI's `GetPrimitiveArrayCritical`/`ReleasePrimitiveArrayCritical`: pins the array in place, no copy |
| `jniCopying` | JNI's ordinary `GetByteArrayElements`/`ReleaseByteArrayElements`: the JVM is free to copy |

Run it for real (a short profile — the default warmup/measurement takes several minutes):

```
mvn -q -pl native-interop-bench -am install -DskipTests   # builds libjniz.so and benchmarks.jar
java -jar native-interop-bench/target/benchmarks.jar -f 1 -wi 2 -i 2 -r 1
```

## `Linker.Option.critical` does not exist on this JDK — a correction made while building this

The plan for this exercise, following an early reading of the FFM API's design discussions,
assumed `java.lang.foreign.Linker.Option` had a `critical(boolean allowHeapAccess)` factory letting
a downcall take a heap `byte[]` directly, no copy. Checked directly against this JDK
(`javap java.lang.foreign.Linker$Option`, Temurin 25.0.1) before writing any code:

```
public interface java.lang.foreign.Linker$Option {
  public static java.lang.foreign.Linker$Option firstVariadicArg(int);
  public static java.lang.foreign.Linker$Option captureCallState(java.lang.String...);
  public static java.lang.foreign.StructLayout captureStateLayout();
  public static java.lang.foreign.Linker$Option isTrivial();
}
```

No `critical` method. The real "critical, zero-copy heap array access" primitive on this JDK is
JNI's own `GetPrimitiveArrayCritical` — a genuinely different API, from before FFM existed, not an
FFM feature at all. So the comparison this module actually runs is: the FFM wrapper's
copy-based approach vs. JNI's two techniques for the same thing (pin vs. copy) — which is a more
honest three-way comparison than the original plan's, and the point (copying has a cost) survives
intact.

## What the numbers actually showed (this JDK, this sandbox, x86_64, 2026-09-14)

```
Benchmark                           (payloadSize)  Mode  Cnt    Score   Error  Units
ZlibBenchmarks.ffmZlibCodec                  4096  avgt    2   57.182          us/op
ZlibBenchmarks.ffmZlibCodec                 65536  avgt    2  817.693          us/op
ZlibBenchmarks.jniCopying                    4096  avgt    2   14.429          us/op
ZlibBenchmarks.jniCopying                   65536  avgt    2  203.423          us/op
ZlibBenchmarks.jniCriticalZeroCopy           4096  avgt    2   14.280          us/op
ZlibBenchmarks.jniCriticalZeroCopy          65536  avgt    2  206.403          us/op
```

Two real findings, not just numbers to skim past:

- **The two JNI paths are statistically indistinguishable** here. On this glibc/HotSpot
  combination, `GetByteArrayElements` on a `byte[]` apparently isn't copying either (the JVM is
  *allowed* to copy, not required to) — `Linker.Option.critical`'s absence turns out not to be
  costing anything relative to the "ordinary" JNI path on this platform, only relative to the FFM
  path below.
- **`ffmZlibCodec` is ~4x slower than either JNI path at both sizes.** `ZlibCodec.compress()`
  allocates three fresh native segments (`source`, `dest`, `destLen`) through its `Arena` on every
  call and copies the payload in and back out via `MemorySegment.copy`, instead of reusing a
  scratch buffer across calls the way a tuned wrapper would; each `MethodHandle.invokeExact` also
  carries more per-call overhead than a JNI trampoline for a call this short. `ZlibCodec` was
  written for the graded exercise's "no leaked segments" and lifetime-clarity goals, not for
  this benchmark — this number is what that tradeoff costs, measured rather than assumed. A tuned
  variant (one arena, reused scratch segments sized to the largest payload seen) would be a
  reasonable follow-up, deliberately left undone here since the exercise's ask was the comparison,
  not a fully optimized FFM wrapper.

This is exactly the project's stated ethos: run it, then find out what actually happened, and say
so — including when a plan's own factual assumption turns out to be wrong.

## The smoke test vs. the real benchmark

`ZlibBenchmarksSmokeTest` (part of the normal `mvn verify` run) checks all three paths produce
byte-identical compressed output for a small payload — it does not go through the JMH harness at
all, the same reason `concurrency-jmm-jcstress` doesn't run a real jcstress suite during `verify`:
a real benchmark run takes minutes (a JVM fork per method x parameter combination) and doesn't
belong in the build.
