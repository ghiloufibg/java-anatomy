# Phase 4 exercise (JMH half) — Vector API vs. scalar

Benchmarks [`ScalarEvaluator` vs. `VectorEvaluator`](../language-evolution/README.md) on the same
[`TypedExpr`](../language-evolution/src/main/java/dev/sevenrungs/languageevolution/exercise/TypedExpr.java)
(`(x * y) + 1`) at a few realistic array sizes — the exercise's "prove with JMH that the Vector API
backend beats the scalar path at realistic sizes" requirement.

Run it for real (a short profile — the default warmup/measurement takes several minutes):

```
mvn -q -pl language-evolution-bench -am install -DskipTests
java --add-modules jdk.incubator.vector -jar language-evolution-bench/target/benchmarks.jar -f 1 -wi 2 -i 2 -r 1
```

## What the numbers actually showed (this JDK, this sandbox, x86_64 AVX-512, 2026-09-14)

```
Benchmark                   (length)  Mode  Cnt     Score   Error  Units
EvaluatorBenchmarks.scalar        64  avgt    2     2.415          us/op
EvaluatorBenchmarks.scalar      4096  avgt    2   134.137          us/op
EvaluatorBenchmarks.scalar     65536  avgt    2  2265.418          us/op
EvaluatorBenchmarks.vector        64  avgt    2     0.367          us/op
EvaluatorBenchmarks.vector      4096  avgt    2    26.790          us/op
EvaluatorBenchmarks.vector     65536  avgt    2   434.333          us/op
```

The Vector API backend is consistently **~5x faster than the scalar path at every size tried**
(6.6x at 64 elements, 5.0x at 4096, 5.2x at 65536) — this hardware's `SPECIES_PREFERRED` is
512-bit/16 float lanes (AVX-512), so the ceiling from lane-parallelism alone is in that
neighbourhood for an expression this arithmetic-bound (three lane ops per node: `Mul`, `Add`, and
the loads). The exercise's claim holds, measured rather than assumed, and holds at the smallest
size tried too — worth noting since the artifact's own `Cosine` rung specifically warns that a
Vector API shape can silently fall back to scalar and end up *slower* than the loop it replaced;
that risk didn't materialize here, but it's exactly why this benchmark exists rather than trusting
the API by inspection.

## The smoke test vs. the real benchmark

`EvaluatorBenchmarksSmokeTest` (part of the normal `mvn verify` run) checks the two backends agree
byte-for-byte on a length that isn't a multiple of any realistic species width — it does not go
through the JMH harness at all, the same reason `ZlibBenchmarksSmokeTest` (`native-interop-bench`)
and `concurrency-jmm-jcstress` don't run their real workloads during `verify`: a real benchmark or
stress run takes minutes and doesn't belong in the build.
