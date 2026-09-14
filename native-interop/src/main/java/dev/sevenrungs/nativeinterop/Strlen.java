package dev.sevenrungs.nativeinterop;

// Calling libc from Java without JNI: JEP 454 (final in JDK 22).
// Every native call is (1) a symbol, (2) a FunctionDescriptor, (3) a downcall MethodHandle.
// The Arena owns lifetime: close it and every segment it allocated becomes unusable.
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public final class Strlen {

  /** The three parts of every downcall, done once and reused for every string. */
  static MethodHandle strlenHandle() {
    Linker linker = Linker.nativeLinker();
    SymbolLookup libc = linker.defaultLookup(); // libc on Linux/macOS, msvcrt on Windows
    return linker.downcallHandle(
        libc.find("strlen").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)); // size_t strlen(const
    // char*)
  }

  /** Copies {@code text} into a confined arena as a NUL-terminated C string and calls strlen. */
  static long strlenOf(MethodHandle strlen, String text) throws Throwable {
    try (Arena arena = Arena.ofConfined()) { // confined = this thread only
      MemorySegment cstr = arena.allocateFrom(text); // NUL-terminated UTF-8
      return (long) strlen.invokeExact(cstr);
    }
  }

  public static void main(String[] args) throws Throwable {
    long n = strlenOf(strlenHandle(), "Hello, Panama!");
    System.out.println(n); // 14
    // java --enable-native-access=ALL-UNNAMED Strlen
    // Without the flag you get a warning today; JEP 472 turns it into an error later.
  }
}
