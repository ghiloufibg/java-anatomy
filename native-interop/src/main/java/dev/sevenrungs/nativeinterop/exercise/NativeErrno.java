package dev.sevenrungs.nativeinterop.exercise;

// Capturing errno from a failing native call (JEP 454's Linker.Option.captureCallState), surfaced
// as a typed exception instead of the caller having to know libc's error-reporting convention.
// zlib's own functions return a Z_* code, not errno (see ZlibCodec.ZlibException), so this is a
// standalone example: libc's open() on a path that cannot exist.
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

public final class NativeErrno {

  private static final int O_RDONLY = 0;

  private NativeErrno() {}

  /**
   * Opens {@code path} read-only via libc's {@code open()}. On failure, throws {@link
   * NativeIoException} carrying the real {@code errno} the kernel set, captured via {@link
   * Linker.Option#captureCallState}, instead of only "it failed".
   */
  public static int openReadOnly(String path) {
    Linker linker = Linker.nativeLinker();
    SymbolLookup libc = linker.defaultLookup();
    Linker.Option captureErrno = Linker.Option.captureCallState("errno");
    StructLayout stateLayout = Linker.Option.captureStateLayout();
    VarHandle errnoHandle = stateLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    MethodHandle open =
        linker.downcallHandle(
            libc.find("open").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
            captureErrno);

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment capturedState = arena.allocate(stateLayout);
      MemorySegment cpath = arena.allocateFrom(path);
      int fd = (int) open.invoke(capturedState, cpath, O_RDONLY);
      if (fd < 0) {
        int errno = (int) errnoHandle.get(capturedState, 0L);
        throw new NativeIoException("open", path, errno);
      }
      return fd;
    } catch (Throwable t) {
      if (t instanceof NativeIoException nio) throw nio;
      if (t instanceof RuntimeException re) throw re;
      throw new RuntimeException(t);
    }
  }

  /** A failing native call's {@code errno}, surfaced as a normal Java exception. */
  public static final class NativeIoException extends RuntimeException {
    private final int errno;

    public NativeIoException(String function, String path, int errno) {
      super(function + "(\"" + path + "\") failed: errno=" + errno);
      this.errno = errno;
    }

    /** The raw {@code errno} value the kernel set (see {@code <errno.h>}, e.g. ENOENT = 2). */
    public int errno() {
      return errno;
    }
  }
}
