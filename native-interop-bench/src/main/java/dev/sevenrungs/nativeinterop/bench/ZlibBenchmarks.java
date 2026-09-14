package dev.sevenrungs.nativeinterop.bench;

// Three ways to get a Java byte[] compressed by native zlib, benchmarked side by side:
//  - ffmZlibCodec: the graded exercise's FFM wrapper (copies into an off-heap MemorySegment)
//  - jniCriticalZeroCopy: JNI's GetPrimitiveArrayCritical, pinning the array in place, no copy
//  - jniCopying: JNI's ordinary GetByteArrayElements, which the JVM may satisfy with a copy
// Run for real with: java -jar target/benchmarks.jar (see README.md for a short profile).
import dev.sevenrungs.nativeinterop.exercise.ZlibCodec;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class ZlibBenchmarks {

  @Param({"4096", "65536"})
  int payloadSize;

  byte[] payload;
  ZlibCodec codec;

  @Setup(Level.Trial)
  public void setup() {
    payload = new byte[payloadSize];
    // A repeating pattern, not random noise: zlib needs something compressible to do real work.
    for (int i = 0; i < payload.length; i++) payload[i] = (byte) ('a' + (i % 17));
    codec = ZlibCodec.open();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    codec.close();
  }

  @Benchmark
  public byte[] ffmZlibCodec() {
    return codec.compress(payload).data();
  }

  @Benchmark
  public byte[] jniCriticalZeroCopy() {
    return JniZlib.compress(payload, 6);
  }

  @Benchmark
  public byte[] jniCopying() {
    return JniZlib.compressCopying(payload, 6);
  }
}
