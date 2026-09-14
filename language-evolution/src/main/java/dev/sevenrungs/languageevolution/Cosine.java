package dev.sevenrungs.languageevolution;

// Vector API (JEP 508, still incubating: --add-modules jdk.incubator.vector).
// The skill is not "use SIMD", it is knowing the shape: species width, lane masks for
// the tail, and which ops C2 actually intrinsifies on your CPU (-XX:+PrintIntrinsics).
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public final class Cosine {
  static final VectorSpecies<Float> S = FloatVector.SPECIES_PREFERRED; // 16 lanes on AVX-512

  /** Cosine similarity of two embeddings: three reductions in one fused loop. */
  static float cosine(float[] a, float[] b) {
    var dot = FloatVector.zero(S);
    var na = FloatVector.zero(S);
    var nb = FloatVector.zero(S);
    int i = 0, bound = S.loopBound(a.length);
    for (; i < bound; i += S.length()) {
      var va = FloatVector.fromArray(S, a, i);
      var vb = FloatVector.fromArray(S, b, i);
      dot = va.fma(vb, dot); // vfmadd: one rounding, one instruction
      na = va.fma(va, na);
      nb = vb.fma(vb, nb);
    }
    if (i < a.length) { // masked tail instead of a scalar loop
      var m = S.indexInRange(i, a.length);
      var va = FloatVector.fromArray(S, a, i, m);
      var vb = FloatVector.fromArray(S, b, i, m);
      dot = va.fma(vb, dot);
      na = va.fma(va, na);
      nb = vb.fma(vb, nb);
    }
    float d = dot.reduceLanes(VectorOperators.ADD); // cross-lane: comparatively expensive, once
    return (float)
        (d / Math.sqrt(na.reduceLanes(VectorOperators.ADD) * nb.reduceLanes(VectorOperators.ADD)));
  }

  public static void main(String[] args) {
    float[] a = new float[1536], b = new float[1536];
    for (int i = 0; i < a.length; i++) {
      a[i] = (float) Math.sin(i);
      b[i] = (float) Math.cos(i * 0.5);
    }
    System.out.println(S + " -> " + cosine(a, b));
  }
  // Verify it vectorised, don't trust it: -XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics
  // after enough warmup to reach C2. On this sandbox's hardware (AVX-512, SPECIES_PREFERRED =
  // 512-bit / 16 float lanes), the compiled method inlines against the concrete Float512Vector
  // class rather than a generic boxed path - see language-evolution/README.md for the real
  // captured output. When a shape is not intrinsified, the API silently falls back to scalar
  // Java, and that is slower than the plain loop you replaced.
}
