package dev.sevenrungs.nativeinterop.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sevenrungs.nativeinterop.exercise.NativeErrno.NativeIoException;
import org.junit.jupiter.api.Test;

class NativeErrnoTest {

  private static final int ENOENT = 2; // <errno.h>: No such file or directory

  @Test
  void failingOpenSurfacesTheRealErrnoAsATypedException() {
    NativeIoException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            NativeIoException.class,
            () -> NativeErrno.openReadOnly("/definitely/not/a/real/path-xyz123"));
    assertEquals(ENOENT, ex.errno());
    assertTrue(ex.getMessage().contains("errno=" + ENOENT));
  }

  @Test
  void succeedsForAPathThatActuallyExists() {
    int fd = NativeErrno.openReadOnly("/etc/hostname");
    assertTrue(fd >= 0);
  }
}
