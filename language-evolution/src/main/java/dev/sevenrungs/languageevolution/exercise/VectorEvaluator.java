package dev.sevenrungs.languageevolution.exercise;

// The Vector API backend the exercise asks for: same evaluation as ScalarEvaluator, one
// species-width lane group at a time, with a masked tail for lengths that aren't a multiple of
// the species width - the same technique Cosine.java uses for the graded rung.
import java.util.Map;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

public final class VectorEvaluator {

  static final VectorSpecies<Float> S = FloatVector.SPECIES_PREFERRED;

  private VectorEvaluator() {}

  /** Evaluates {@code expr} at every index {@code 0..length-1}, {@link #S}'s width at a time. */
  public static float[] evaluate(TypedExpr expr, Map<String, float[]> variables, int length) {
    float[] result = new float[length];
    int i = 0, bound = S.loopBound(length);
    for (; i < bound; i += S.length()) {
      evalLane(expr, variables, i, null).intoArray(result, i);
    }
    if (i < length) {
      VectorMask<Float> m = S.indexInRange(i, length);
      evalLane(expr, variables, i, m).intoArray(result, i, m);
    }
    return result;
  }

  private static FloatVector evalLane(
      TypedExpr expr, Map<String, float[]> variables, int i, VectorMask<Float> m) {
    return switch (expr) {
      case Const(var value) -> FloatVector.broadcast(S, value);
      case Var(var name) -> loadLane(variables.get(name), i, m);
      case Add(var l, var r) -> evalLane(l, variables, i, m).add(evalLane(r, variables, i, m));
      case Sub(var l, var r) -> evalLane(l, variables, i, m).sub(evalLane(r, variables, i, m));
      case Mul(var l, var r) -> evalLane(l, variables, i, m).mul(evalLane(r, variables, i, m));
      case Div(var l, var r) -> evalLane(l, variables, i, m).div(evalLane(r, variables, i, m));
    };
  }

  private static FloatVector loadLane(float[] array, int i, VectorMask<Float> m) {
    return m == null ? FloatVector.fromArray(S, array, i) : FloatVector.fromArray(S, array, i, m);
  }
}
