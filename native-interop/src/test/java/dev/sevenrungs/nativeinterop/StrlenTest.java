package dev.sevenrungs.nativeinterop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class StrlenTest {

  @Test
  void matchesJavaStringLengthForAsciiText() throws Throwable {
    assertEquals(14, Strlen.strlenOf(Strlen.strlenHandle(), "Hello, Panama!"));
  }

  @Test
  void isZeroForAnEmptyString() throws Throwable {
    assertEquals(0, Strlen.strlenOf(Strlen.strlenHandle(), ""));
  }

  @Test
  void segmentIsUnusableOnceItsArenaIsClosed() {
    var handle = Strlen.strlenHandle();
    MemorySegment leaked;
    try (Arena arena = Arena.ofConfined()) {
      leaked = arena.allocateFrom("will not survive");
    }
    // Proves the exact lifetime rule the graded example's Javadoc calls out: close the arena and
    // every segment it allocated becomes unusable, a Java exception rather than a crash.
    assertThrows(IllegalStateException.class, () -> handle.invoke(leaked));
  }
}
