package dev.sevenrungs.languageevolution.bench;

// Proves (or disproves, measured either way) the exercise's claim: the Vector API backend beats
// the scalar path at realistic array sizes. Run for real with:
// java --add-modules jdk.incubator.vector -jar target/benchmarks.jar (see README.md).
import dev.sevenrungs.languageevolution.exercise.Add;
import dev.sevenrungs.languageevolution.exercise.Const;
import dev.sevenrungs.languageevolution.exercise.Mul;
import dev.sevenrungs.languageevolution.exercise.ScalarEvaluator;
import dev.sevenrungs.languageevolution.exercise.TypedExpr;
import dev.sevenrungs.languageevolution.exercise.Var;
import dev.sevenrungs.languageevolution.exercise.VectorEvaluator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "--add-modules=jdk.incubator.vector")
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class EvaluatorBenchmarks {

  @Param({"64", "4096", "65536"})
  int length;

  TypedExpr expr;
  Map<String, float[]> variables;

  @Setup(Level.Trial)
  public void setup() {
    // (x * y) + 1, the same worked example ScalarEvaluatorTest checks correctness against.
    expr = new Add(new Mul(new Var("x"), new Var("y")), new Const(1));
    float[] x = new float[length];
    float[] y = new float[length];
    for (int i = 0; i < length; i++) {
      x[i] = i;
      y[i] = length - i;
    }
    variables = Map.of("x", x, "y", y);
  }

  @Benchmark
  public float[] scalar() {
    return ScalarEvaluator.evaluate(expr, variables, length);
  }

  @Benchmark
  public float[] vector() {
    return VectorEvaluator.evaluate(expr, variables, length);
  }
}
