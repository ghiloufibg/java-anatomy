# Phase 4 — Language evolution: patterns, sealed types, Valhalla, the Vector API

You know the syntax of records, sealed types and pattern switches. The expert-level content is
what they do to *program structure*: sealed + records + exhaustive switch gives Java algebraic
data types, and the compiler's exhaustiveness rules (JLS §14.11.1.1) decide which refactorings are
safe. Two projects go further and change the runtime itself: the Vector API exposes real SIMD, and
Project Valhalla removes object identity.

## Read in this order

1. JLS §14.30 (patterns) and §14.11.1.1 (exhaustiveness), then JEP 441's section on dominance.
2. "State of Valhalla" parts 1 to 3 on the [project page](https://openjdk.org/projects/valhalla/),
   then [JEP 401](https://openjdk.org/jeps/401) in full.
3. [JEP 508](https://openjdk.org/jeps/508) and the `jdk.incubator.vector` package javadoc.
4. The amber-dev and valhalla-dev archives for the month a feature you use was finalised.

## The three rungs

| Difficulty | Class | Run it |
|---|---|---|
| medium | `Algebra` | `mvn -q -pl language-evolution compile exec:exec -Dexec.mainClass=dev.sevenrungs.languageevolution.Algebra` |
| hard | `Cosine` | `mvn -q -pl language-evolution compile exec:exec -Dexec.mainClass=dev.sevenrungs.languageevolution.Cosine` |
| guru | `IdentityToday` (see below) | `mvn -q -pl language-evolution compile exec:exec -Dexec.mainClass=dev.sevenrungs.languageevolution.IdentityToday` |

`Cosine` needs `--add-modules jdk.incubator.vector` (JEP 508 is still incubating in JDK 25);
`IdentityToday` needs `jol-core` on the classpath and `-Djol.magicFieldOffset=true` (see below).
Both are already wired into `language-evolution/pom.xml`'s `exec:exec` arguments and Surefire's
`argLine`.

**`Algebra`'s exhaustiveness, proven, not just claimed**: deleting the `case Var v -> v;` arm from
`simplify`'s switch (leaving `Var` in the `permits` clause) makes `javac` reject the file with
`the switch expression does not cover all possible input values` — captured for real while writing
this class, not paraphrased from the JEP. (Removing `Var` from `permits` instead trips an earlier,
different error — `class is not allowed to extend sealed class` — since the record still
implements the interface; both are exhaustiveness working as designed, just at different points.)

**`Cosine`'s vectorization, proven, not just claimed**: on this sandbox's hardware,
`FloatVector.SPECIES_PREFERRED` is `Species[float, 16, S_512_BIT]` (AVX-512, 16 lanes). Running
with `-XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics` after enough warmup to reach C2 shows
the JIT compiling and heavily inlining against the concrete `Float512Vector` class — real SIMD, not
a boxed fallback.

## The guru rung: why it's `IdentityToday`, not a real Valhalla value class

The artifact's own guru rung is a Project Valhalla value class (JEP 401): `value record Complex`,
`IdentityException`, a JOL-visible flattened array. **This needs a Project Valhalla early-access
JDK build** (`jdk.java.net/valhalla`) — `value record` syntax and `IdentityException` don't exist
in mainline JDK 25 and won't until JEP 401 itself ships.

Checked whether one could be fetched into this sandbox before writing any code: `curl` through
this environment's egress proxy to both `jdk.java.net` and `download.java.net` returns `403`
(`CONNECT tunnel failed`) — the same domains `WebFetch` had already refused earlier in this
project's own development. **This rung cannot be built or run here.** Rather than skip it silently
or fake output that was never produced, the artifact's own `Valhalla.java` is kept verbatim as a
non-compiled reference at [`docs/Valhalla.java.reference`](docs/Valhalla.java.reference) (its
`.reference` extension, and living outside `src/`, keep Maven from ever touching it) for anyone
checking this repository out on a machine with real internet access to `jdk.java.net/valhalla`.

**`IdentityToday.java`** is the real, runnable substitute: it proves, on stock JDK 25, the "before"
half of every contrast JEP 401 changes —

1. Two structurally-equal `record`s are `equals()` but not `==` (identity survives; a value class
   would make `==` compare state instead).
2. `synchronized` completes normally on a record (there is no `IdentityException` to catch here).
3. An array of records has real, measurable per-instance overhead — see below.

**A second, unplanned real finding while writing `IdentityToday`**: JOL 0.17's default
Unsafe-based field-offset lookup refuses record classes outright on this JDK —
`can't get field offset on a record class`. `LayoutProbe` in `jvm-internals` never hit this because
its `Node` class isn't a record. The fix, confirmed empirically, is JOL's own documented
workaround flag, `-Djol.magicFieldOffset=true`, already wired into this module's `pom.xml`. With
it, a real 4096-element `Complex[]` array reports:

```
     COUNT       AVG       SUM   DESCRIPTION
         1     16400     16400   [Ldev.sevenrungs.languageevolution.IdentityToday$Complex;
      4096        32    131072   dev.sevenrungs.languageevolution.IdentityToday$Complex
         1        16        16   dev.sevenrungs.languageevolution.IdentityToday$Signal
      4098              147488   (total)
```

131072 bytes (32 bytes × 4096) is real per-instance object overhead a Valhalla value class with
flattening could collapse away entirely — the array would hold the raw `double, double` payload
inline instead of 4096 separate headed objects plus 4096 references.

## The exercise: a typed interpreter that is also a SIMD kernel

> Build a small expression language with a sealed AST, a type checker written as exhaustive
> switches, and an evaluator that can run element-wise over arrays. Give the evaluator a Vector
> API backend... On a Valhalla EA build, turn the AST's numeric leaves into value records.

What's here for it, under `exercise/`:

- **`TypedExpr`** — the sealed AST (`Const`, `Var`, `Add`, `Sub`, `Mul`, `Div`), each its own
  top-level record file (a `public sealed interface`'s permitted types must each live in their own
  file — nesting them inside the interface itself doesn't let its own `permits` clause resolve
  them, discovered while writing this).
- **`TypeChecker`** — exhaustive-switch shape checking: every `Var` must name a known input array.
- **`ScalarEvaluator`** — element-wise evaluation, one `float` at a time.
- **`VectorEvaluator`** — the same evaluation, Vector-API-backed, with a masked tail for lengths
  that aren't a multiple of the species width.
- The JMH benchmark proving the Vector backend actually wins lives in the sibling module,
  [`language-evolution-bench`](../language-evolution-bench/README.md), for the same reason
  `native-interop-bench` and `concurrency-jmm-jcstress` are their own modules.
- The exercise's Valhalla step (numeric leaves as value records) gets the same honest treatment as
  the graded rung above: not buildable in this sandbox, not faked.

### The refactoring write-up: adding a node type

Adding a seventh node (say, `Neg`) to `TypedExpr` and its `permits` clause makes `TypeChecker`,
`ScalarEvaluator` and `VectorEvaluator`'s switches all fail to compile with the same
`the switch expression does not cover all possible input values` error `Algebra`'s test
demonstrates — one compiler error per file that needs a new arm, found the moment you add the
type, not at runtime. What the compiler *cannot* catch: whether the new arm's logic is
*semantically* correct (e.g. `Neg`'s scalar and vector implementations disagreeing on sign) —
that's still `ScalarEvaluatorTest`/`VectorEvaluatorTest`'s job, specifically the property they
already assert (both backends must agree on the same input), not the compiler's.
