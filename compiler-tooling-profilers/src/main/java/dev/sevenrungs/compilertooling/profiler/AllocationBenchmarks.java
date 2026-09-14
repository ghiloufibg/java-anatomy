package dev.sevenrungs.compilertooling.profiler;

// Measures the per-call overhead each Instrumenter adds to Workload.allocateObject() - the
// exercise's "measure the overhead of each with JMH" requirement. All four variants (baseline,
// classfile, asm, bytebuddy) are invoked through the same MethodHandle mechanism, each defined in
// its own isolated ClassLoader in @Setup, so the only thing that differs between benchmark
// methods is the instrumentation itself, not the invocation path measuring it.
// Run for real with: java -jar target/profilers.jar (see README.md for a short profile).
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class AllocationBenchmarks {

  private static final String OWNER = "dev/sevenrungs/compilertooling/profiler/Workload";

  private MethodHandle baselineHandle;
  private MethodHandle classFileHandle;
  private MethodHandle asmHandle;
  private MethodHandle byteBuddyHandle;

  @Setup(Level.Trial)
  public void setup() throws Throwable {
    byte[] original = Files.readAllBytes(Path.of("target", "classes", OWNER + ".class"));

    baselineHandle = handleFor(defineIsolated(original));
    classFileHandle =
        handleFor(defineIsolated(new ClassFileApiInstrumenter().instrument(OWNER, original)));
    asmHandle = handleFor(defineIsolated(new AsmInstrumenter().instrument(OWNER, original)));
    byteBuddyHandle =
        handleFor(defineIsolated(new ByteBuddyInstrumenter().instrument(OWNER, original)));
  }

  @Benchmark
  public Object baseline() throws Throwable {
    return baselineHandle.invoke();
  }

  @Benchmark
  public Object classFileApi() throws Throwable {
    return classFileHandle.invoke();
  }

  @Benchmark
  public Object asm() throws Throwable {
    return asmHandle.invoke();
  }

  @Benchmark
  public Object byteBuddy() throws Throwable {
    return byteBuddyHandle.invoke();
  }

  private static Class<?> defineIsolated(byte[] bytes) {
    // IsolatedDefinition renames the class to a fresh name first (see its Javadoc): once the
    // name genuinely doesn't exist anywhere else on the classpath, an ordinary findClass override
    // is enough - no need to fight the default loadClass's parent-first delegation.
    var renamed = IsolatedDefinition.rename(bytes, OWNER);
    ClassLoader loader =
        new ClassLoader(AllocationBenchmarks.class.getClassLoader()) {
          @Override
          protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(renamed.binaryName())) throw new ClassNotFoundException(name);
            return defineClass(name, renamed.bytes(), 0, renamed.bytes().length);
          }
        };
    try {
      return loader.loadClass(renamed.binaryName());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  private static MethodHandle handleFor(Class<?> workloadVariant) throws Throwable {
    return MethodHandles.publicLookup()
        .findStatic(workloadVariant, "allocateObject", MethodType.methodType(Object.class));
  }
}
