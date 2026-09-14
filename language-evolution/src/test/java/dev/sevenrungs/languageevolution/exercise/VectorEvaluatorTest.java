package dev.sevenrungs.languageevolution.exercise;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VectorEvaluatorTest {

  @Test
  void matchesTheScalarEvaluatorOnASimpleExpression() {
    float[] x = {1, 2, 3, 4, 5};
    float[] y = {10, 20, 30, 40, 50};
    var expr = new Add(new Mul(new Var("x"), new Var("y")), new Const(1));
    var vars = Map.of("x", x, "y", y);

    assertArrayEquals(
        ScalarEvaluator.evaluate(expr, vars, x.length),
        VectorEvaluator.evaluate(expr, vars, x.length));
  }

  // Every length here straddles a species-width boundary somewhere (1, 2, 4, 8, 16 lanes are all
  // realistic on real hardware): the property under test is that the masked tail agrees with the
  // scalar path, not just the full-lane main loop.
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 3, 7, 15, 16, 17, 33, 100, 137})
  void agreesWithTheScalarEvaluatorAtEveryLength(int length) {
    float[] x = new float[length];
    float[] y = new float[length];
    for (int i = 0; i < length; i++) {
      x[i] = i + 1;
      y[i] = (i % 5) + 1; // never zero, so Div below never divides by zero
    }
    var expr = new Sub(new Div(new Var("x"), new Var("y")), new Const(0.5f));
    var vars = Map.of("x", x, "y", y);

    assertArrayEquals(
        ScalarEvaluator.evaluate(expr, vars, length),
        VectorEvaluator.evaluate(expr, vars, length),
        1e-4f);
  }

  @Test
  void aBareConstantFillsEveryLane() {
    int length = 41; // deliberately not a multiple of any realistic species width
    float[] expected = new float[length];
    IntStream.range(0, length).forEach(i -> expected[i] = 7);
    assertArrayEquals(expected, VectorEvaluator.evaluate(new Const(7), Map.of(), length));
  }
}
