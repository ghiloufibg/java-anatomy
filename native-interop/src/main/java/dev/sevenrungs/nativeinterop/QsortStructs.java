package dev.sevenrungs.nativeinterop;

// Structs, layouts and upcalls: qsort() over an array of C structs with a Java comparator.
// Guru details hidden in here: layout paths generate VarHandles that already know the
// struct's stride; the upcall stub is a real C function pointer whose lifetime is the arena's.
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

public final class QsortStructs {

  // struct entry { int32_t id; int32_t pad; double score; };  (natural alignment, 16 bytes)
  static final StructLayout ENTRY =
      MemoryLayout.structLayout(
          JAVA_INT.withName("id"), MemoryLayout.paddingLayout(4), JAVA_DOUBLE.withName("score"));
  static final VarHandle ID = ENTRY.varHandle(MemoryLayout.PathElement.groupElement("id"));
  static final VarHandle SCORE = ENTRY.varHandle(MemoryLayout.PathElement.groupElement("score"));

  // int cmp(const void* a, const void* b): our Java method, seen from C
  static int compare(MemorySegment a, MemorySegment b) {
    return Double.compare((double) SCORE.get(a, 0L), (double) SCORE.get(b, 0L));
  }

  /** One sorted struct: proves the whole entry moved together, not just the sort key. */
  record Entry(int id, double score) {}

  /**
   * Assigns each score an id (its original index) and sorts the (id, score) structs in place via
   * native qsort() and a Java-side comparator upcall.
   */
  static Entry[] qsortByScore(double[] scores) throws Throwable {
    Linker linker = Linker.nativeLinker();
    MethodHandle qsort =
        linker.downcallHandle(
            linker.defaultLookup().find("qsort").orElseThrow(),
            FunctionDescriptor.ofVoid(
                ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ADDRESS));

    try (Arena arena = Arena.ofConfined()) {
      int n = scores.length;
      MemorySegment entries = arena.allocate(ENTRY, n); // contiguous array of structs
      for (int i = 0; i < n; i++) {
        ID.set(entries, (long) i * ENTRY.byteSize(), i);
        SCORE.set(entries, (long) i * ENTRY.byteSize(), scores[i]);
      }

      // The comparator receives raw pointers; re-size them to ENTRY so bounds checks work.
      MethodHandle cmp =
          MethodHandles.lookup()
              .findStatic(
                  QsortStructs.class,
                  "compare",
                  MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class));
      FunctionDescriptor cmpDesc =
          FunctionDescriptor.of(
              JAVA_INT, ADDRESS.withTargetLayout(ENTRY), ADDRESS.withTargetLayout(ENTRY));
      MemorySegment cmpStub = linker.upcallStub(cmp, cmpDesc, arena); // C function pointer

      qsort.invokeExact(entries, (long) n, ENTRY.byteSize(), cmpStub);

      Entry[] sorted = new Entry[n];
      for (int i = 0; i < n; i++) {
        long offset = (long) i * ENTRY.byteSize();
        sorted[i] = new Entry((int) ID.get(entries, offset), (double) SCORE.get(entries, offset));
      }
      return sorted;
    } // arena closes: entries AND the upcall stub are freed together. Call qsort now =
    // IllegalStateException, never a segfault.
  }

  public static void main(String[] args) throws Throwable {
    double[] scores = {3.5, 0.25, 9.0, 1.0, 4.4};
    for (Entry e : qsortByScore(scores)) {
      System.out.printf("id=%d score=%.2f%n", e.id(), e.score());
    }
    // java --enable-native-access=ALL-UNNAMED QsortStructs
  }
}
