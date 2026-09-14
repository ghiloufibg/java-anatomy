package dev.sevenrungs.nativeinterop.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sevenrungs.nativeinterop.exercise.ZlibCodec;
import org.junit.jupiter.api.Test;

/**
 * A real JMH run takes minutes (a JVM fork per benchmark method x parameter combination) and does
 * not belong in {@code mvn verify}, so this checks the same thing the benchmark measures - the
 * three paths agree - without going through the JMH harness at all. See README.md for how to run
 * the actual benchmark.
 */
class ZlibBenchmarksSmokeTest {

  private static final int LEVEL = 6;

  @Test
  void allThreePathsProduceByteIdenticalCompressedOutput() {
    byte[] payload = new byte[8192];
    for (int i = 0; i < payload.length; i++) payload[i] = (byte) ('a' + (i % 17));

    byte[] jniCritical = JniZlib.compress(payload, LEVEL);
    byte[] jniCopying = JniZlib.compressCopying(payload, LEVEL);
    byte[] ffm;
    try (ZlibCodec codec = ZlibCodec.open()) {
      ffm = codec.compress(payload, LEVEL).data();
    }

    assertTrue(jniCritical.length > 0);
    assertArrayEquals(jniCritical, jniCopying, "the two JNI paths must agree byte-for-byte");
    assertArrayEquals(jniCritical, ffm, "the JNI and FFM paths must agree byte-for-byte");
  }
}
