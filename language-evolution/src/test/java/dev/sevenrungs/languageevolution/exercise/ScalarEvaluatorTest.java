package dev.sevenrungs.languageevolution.exercise;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ScalarEvaluatorTest {

  @Test
  void evaluatesAConstantEverywhere() {
    float[] expected = {2, 2, 2};
    assertArrayEquals(expected, ScalarEvaluator.evaluate(new Const(2), Map.of(), 3));
  }

  @Test
  void evaluatesAVariableElementWise() {
    float[] x = {1, 2, 3, 4};
    assertArrayEquals(x, ScalarEvaluator.evaluate(new Var("x"), Map.of("x", x), 4));
  }

  @Test
  void evaluatesXTimesYPlusOne() {
    float[] x = {1, 2, 3};
    float[] y = {10, 20, 30};
    var expr = new Add(new Mul(new Var("x"), new Var("y")), new Const(1));
    float[] expected = {11, 41, 91};
    assertArrayEquals(expected, ScalarEvaluator.evaluate(expr, Map.of("x", x, "y", y), 3));
  }
}
