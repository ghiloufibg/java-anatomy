package dev.sevenrungs.languageevolution.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import dev.sevenrungs.languageevolution.exercise.Add;
import dev.sevenrungs.languageevolution.exercise.Const;
import dev.sevenrungs.languageevolution.exercise.Mul;
import dev.sevenrungs.languageevolution.exercise.ScalarEvaluator;
import dev.sevenrungs.languageevolution.exercise.Var;
import dev.sevenrungs.languageevolution.exercise.VectorEvaluator;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A real JMH run takes minutes, so this checks the same thing the benchmark measures - scalar and
 * vector backends agree - without going through the JMH harness, mirroring ZlibBenchmarksSmokeTest
 * in native-interop-bench.
 */
class EvaluatorBenchmarksSmokeTest {

  @Test
  void scalarAndVectorBackendsAgreeOnTheBenchmarkExpression() {
    int length = 777; // deliberately not a multiple of any realistic species width
    var expr = new Add(new Mul(new Var("x"), new Var("y")), new Const(1));
    float[] x = new float[length];
    float[] y = new float[length];
    for (int i = 0; i < length; i++) {
      x[i] = i;
      y[i] = length - i;
    }
    var vars = Map.of("x", x, "y", y);

    assertArrayEquals(
        ScalarEvaluator.evaluate(expr, vars, length), VectorEvaluator.evaluate(expr, vars, length));
  }
}
