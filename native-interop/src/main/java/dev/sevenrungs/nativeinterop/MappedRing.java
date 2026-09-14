package dev.sevenrungs.nativeinterop;

// A single-producer / single-consumer ring buffer living in a memory-mapped file,
// shared between two JVM processes. Combines JEP 454 with JLS §17 memory ordering:
// VarHandle access modes on a MemorySegment give you release/acquire on native memory.
// Run producer:   java --enable-native-access=ALL-UNNAMED MappedRing produce
// Run consumer:   java --enable-native-access=ALL-UNNAMED MappedRing consume
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class MappedRing {
  static final long COUNT = 1_000_000; // how many values produce()/consume() move
  static final int SLOTS = 1 << 12; // power of two -> mask
  static final StructLayout HEADER =
      MemoryLayout.structLayout(
          JAVA_LONG.withName("head"), MemoryLayout.paddingLayout(56), // one cache line each:
          JAVA_LONG.withName("tail"), MemoryLayout.paddingLayout(56)); // no false sharing
  static final VarHandle HEAD = HEADER.varHandle(MemoryLayout.PathElement.groupElement("head"));
  static final VarHandle TAIL = HEADER.varHandle(MemoryLayout.PathElement.groupElement("tail"));
  static final long DATA = HEADER.byteSize();

  /** Maps {@code path} (created if absent) as a shared segment sized for the header + ring. */
  static MemorySegment mapRing(Path path, Arena arena) throws Exception {
    try (FileChannel ch =
        FileChannel.open(
            path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      return ch.map(FileChannel.MapMode.READ_WRITE, 0, DATA + SLOTS * 8L, arena); // arena: shared
    }
  }

  static void produce(MemorySegment ring, long count) {
    for (long v = 1; v <= count; v++) {
      long tail = (long) TAIL.getAcquire(ring, 0L);
      while (tail - (long) HEAD.getAcquire(ring, 0L) >= SLOTS) Thread.onSpinWait(); // full
      ring.setAtIndex(JAVA_LONG, (DATA / 8) + (tail & (SLOTS - 1)), v); // plain write...
      TAIL.setRelease(ring, 0L, tail + 1); // ...published by release
    }
  }

  static long consume(MemorySegment ring, long count) {
    long sum = 0, head = (long) HEAD.getAcquire(ring, 0L);
    while (head < count) {
      while ((long) TAIL.getAcquire(ring, 0L) == head) Thread.onSpinWait(); // empty
      sum += ring.getAtIndex(JAVA_LONG, (DATA / 8) + (head & (SLOTS - 1))); // acquire orders this
      HEAD.setRelease(ring, 0L, ++head);
    }
    return sum;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args[0].isBlank()) {
      throw new IllegalArgumentException("usage: MappedRing <produce|consume> [ring-file]");
    }
    Path path = Path.of(args.length > 1 && !args[1].isBlank() ? args[1] : "/dev/shm/ring.bin");
    try (Arena arena = Arena.ofShared()) { // shared: any thread (and process) may touch it
      MemorySegment ring = mapRing(path, arena);
      if (args[0].equals("produce")) {
        produce(ring, COUNT);
      } else {
        System.out.println(consume(ring, COUNT)); // 500000500000
      }
    }
    // Why this is guru territory: the JLS only defines ordering for Java fields; the FFM API
    // extends the same access modes (JEP 454 §"Memory access") to off-heap memory, and the
    // mapping makes two processes' memory systems the ones that must agree. Prove it under
    // jcstress-style stress, then try replacing setRelease with a plain set and watch it break.
  }
}
