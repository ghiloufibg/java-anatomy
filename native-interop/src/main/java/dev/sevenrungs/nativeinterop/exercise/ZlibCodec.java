package dev.sevenrungs.nativeinterop.exercise;

// A hand-written idiomatic Java wrapper over zlib's compress2()/uncompress(), the exercise's
// "bind a real library, then try to break it" (zlib picked over libsodium: this sandbox has
// zlib1g-dev's headers for reference, but no libsodium-dev headers).
//
// Deliberately NOT jextract-generated: jextract is a separate LLVM-based release artifact, not a
// Maven dependency, and isn't installed here. The graded rungs in this same module (Strlen,
// QsortStructs) already hand-write their FunctionDescriptor/MethodHandle bindings directly rather
// than through generated code, so this exercise does the same rather than silently skipping a
// step or pretending a tool ran that didn't.
//
// A ZlibCodec owns one Arena for its whole lifetime (arena-scoped resources, no leaked segments):
// every MemorySegment it allocates dies when the codec is closed, and any segment handed back out
// of this class stays valid only while the codec is open.
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

public final class ZlibCodec implements AutoCloseable {

  /** {@code compressed} plus the original length {@code uncompress} needs to size its buffer. */
  public record Compressed(byte[] data, int originalLength) {}

  private final Arena arena;
  private final MethodHandle compressBound;
  private final MethodHandle compress2;
  private final MethodHandle uncompress;

  private ZlibCodec(Arena arena) {
    this.arena = arena;
    Linker linker = Linker.nativeLinker();
    SymbolLookup libz = SymbolLookup.libraryLookup("libz.so.1", arena);
    this.compressBound =
        linker.downcallHandle(
            libz.find("compressBound").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
    this.compress2 =
        linker.downcallHandle(
            libz.find("compress2").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
    this.uncompress =
        linker.downcallHandle(
            libz.find("uncompress").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
  }

  /** Opens a codec with its own confined arena; close it (or use try-with-resources) when done. */
  public static ZlibCodec open() {
    return new ZlibCodec(Arena.ofConfined());
  }

  /** Allocates {@code size} bytes of native memory scoped to this codec's arena. */
  MemorySegment allocateScratch(long size) {
    return arena.allocate(size);
  }

  public Compressed compress(byte[] input) {
    return compress(input, 6); // zlib's Z_DEFAULT_COMPRESSION level, spelled out
  }

  public Compressed compress(byte[] input, int level) {
    try {
      MemorySegment source = arena.allocate(Math.max(input.length, 1));
      MemorySegment.copy(input, 0, source, JAVA_BYTE, 0, input.length);

      long bound = (long) compressBound.invokeExact((long) input.length);
      MemorySegment dest = arena.allocate(bound);
      MemorySegment destLen = arena.allocate(JAVA_LONG);
      destLen.set(JAVA_LONG, 0, bound);

      int rc = (int) compress2.invokeExact(dest, destLen, source, (long) input.length, level);
      if (rc != 0) throw new ZlibException("compress2", rc);

      long compressedLen = destLen.get(JAVA_LONG, 0);
      byte[] out = new byte[(int) compressedLen];
      MemorySegment.copy(dest, JAVA_BYTE, 0, out, 0, out.length);
      return new Compressed(out, input.length);
    } catch (Throwable t) {
      throw sneakyRethrow(t);
    }
  }

  public byte[] decompress(Compressed compressed) {
    try {
      MemorySegment source = arena.allocate(Math.max(compressed.data().length, 1));
      MemorySegment.copy(compressed.data(), 0, source, JAVA_BYTE, 0, compressed.data().length);

      MemorySegment dest = arena.allocate(Math.max(compressed.originalLength(), 1));
      MemorySegment destLen = arena.allocate(JAVA_LONG);
      destLen.set(JAVA_LONG, 0, (long) compressed.originalLength());

      int rc = (int) uncompress.invokeExact(dest, destLen, source, (long) compressed.data().length);
      if (rc != 0) throw new ZlibException("uncompress", rc);

      long outLen = destLen.get(JAVA_LONG, 0);
      byte[] out = new byte[(int) outLen];
      MemorySegment.copy(dest, JAVA_BYTE, 0, out, 0, out.length);
      return out;
    } catch (Throwable t) {
      throw sneakyRethrow(t);
    }
  }

  @Override
  public void close() {
    arena.close(); // frees every segment this codec ever allocated, in one step
  }

  private static RuntimeException sneakyRethrow(Throwable t) {
    if (t instanceof RuntimeException re) return re;
    if (t instanceof Error e) throw e;
    return new RuntimeException(t);
  }

  /** A zlib return code that isn't {@code Z_OK} (0): see {@code zlib.h}'s {@code Z_*} constants. */
  public static final class ZlibException extends RuntimeException {
    public ZlibException(String function, int zlibReturnCode) {
      super(function + " returned " + zlibReturnCode + " (zlib.h Z_* error code)");
    }
  }
}
