package dev.sevenrungs.nativeinterop.exercise;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
// WrongThreadException lives in java.lang, not java.lang.foreign - easy to guess wrong; verified
// empirically while writing this test.
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Correctness of the round-trip, plus the exercise's three required safety checks: use-after-close,
 * out-of-bounds access, and cross-thread access to a confined arena must each fail as a Java
 * exception, never a crash.
 */
class ZlibCodecTest {

  @Test
  void roundTripsArbitraryBytes() {
    byte[] original =
        ("Hello, Panama! Hello, Panama! Hello, Panama! Hello, Panama! Hello, Panama!")
            .getBytes(StandardCharsets.UTF_8);
    try (ZlibCodec codec = ZlibCodec.open()) {
      var compressed = codec.compress(original);
      assertTrue(compressed.data().length < original.length, "repeated text should shrink");
      byte[] roundTrip = codec.decompress(compressed);
      assertArrayEquals(original, roundTrip);
    }
  }

  @Test
  void roundTripsEmptyInput() {
    try (ZlibCodec codec = ZlibCodec.open()) {
      var compressed = codec.compress(new byte[0]);
      assertArrayEquals(new byte[0], codec.decompress(compressed));
    }
  }

  @Test
  void badZlibReturnCodeBecomesATypedException() {
    // A corrupted compressed payload makes uncompress() return Z_DATA_ERROR (-3), not crash.
    try (ZlibCodec codec = ZlibCodec.open()) {
      var compressed = codec.compress("some real data to corrupt afterwards".getBytes());
      byte[] corrupted = compressed.data().clone();
      for (int i = 0; i < corrupted.length; i++) corrupted[i] ^= 0x7F;
      var bogus = new ZlibCodec.Compressed(corrupted, compressed.originalLength());
      assertThrows(ZlibCodec.ZlibException.class, () -> codec.decompress(bogus));
    }
  }

  @Test
  void scratchSegmentIsUnusableAfterTheCodecIsClosed() {
    ZlibCodec codec = ZlibCodec.open();
    MemorySegment scratch = codec.allocateScratch(64);
    codec.close();

    assertThrows(
        IllegalStateException.class, () -> scratch.set(ValueLayout.JAVA_BYTE, 0, (byte) 1));
  }

  @Test
  void outOfBoundsAccessIsCaughtNotCrashed() {
    try (ZlibCodec codec = ZlibCodec.open()) {
      MemorySegment scratch = codec.allocateScratch(16);
      assertThrows(
          IndexOutOfBoundsException.class,
          () -> scratch.set(ValueLayout.JAVA_BYTE, 16, (byte) 1)); // one past the end
    }
  }

  @Test
  void crossThreadAccessToAConfinedArenaIsRejected() throws InterruptedException {
    try (ZlibCodec codec = ZlibCodec.open()) {
      MemorySegment scratch = codec.allocateScratch(8);
      AtomicReference<Throwable> caught = new AtomicReference<>();
      Thread other =
          new Thread(
              () -> {
                try {
                  scratch.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
                } catch (Throwable t) {
                  caught.set(t);
                }
              });
      other.start();
      other.join();

      assertTrue(
          caught.get() instanceof WrongThreadException,
          "expected WrongThreadException, got " + caught.get());
    }
  }
}
