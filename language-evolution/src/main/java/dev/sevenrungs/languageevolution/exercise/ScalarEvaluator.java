package dev.sevenrungs.languageevolution.exercise;

// The plain-loop baseline VectorEvaluator is benchmarked against (see language-evolution-bench).
import java.util.Map;

public final class ScalarEvaluator {

  private ScalarEvaluator() {}

  /** Evaluates {@code expr} at every index {@code 0..length-1}, one {@code float} at a time. */
  public static float[] evaluate(TypedExpr expr, Map<String, float[]> variables, int length) {
    float[] result = new float[length];
    for (int i = 0; i < length; i++) result[i] = evalAt(expr, variables, i);
    return result;
  }

  private static float evalAt(TypedExpr expr, Map<String, float[]> variables, int i) {
    return switch (expr) {
      case Const(var value) -> value;
      case Var(var name) -> variables.get(name)[i];
      case Add(var l, var r) -> evalAt(l, variables, i) + evalAt(r, variables, i);
      case Sub(var l, var r) -> evalAt(l, variables, i) - evalAt(r, variables, i);
      case Mul(var l, var r) -> evalAt(l, variables, i) * evalAt(r, variables, i);
      case Div(var l, var r) -> evalAt(l, variables, i) / evalAt(r, variables, i);
    };
  }
}
