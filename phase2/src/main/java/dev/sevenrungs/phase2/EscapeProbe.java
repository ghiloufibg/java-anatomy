package dev.sevenrungs.phase2;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

/**
 * Watch C2's escape analysis delete an allocation. Measure, don't guess: {@code
 * com.sun.management.ThreadMXBean} reports bytes allocated by this thread.
 *
 * <p>Run: {@code java EscapeProbe} - allocation collapses to ~0 after C2 kicks in. Run: {@code java
 * -XX:-DoEscapeAnalysis EscapeProbe} - 16 bytes per Point, forever. Run: {@code java
 * -XX:TieredStopAtLevel=1 EscapeProbe} - C1 only, no scalar replacement. Add {@code
 * -XX:+PrintCompilation} to see the tier transitions (level 3 profile -&gt; level 4 C2).
 *
 * <p>{@link #sum(int)} is a closed-form invariant regardless of what the JIT does with it: {@code
 * acc.x()} accumulates {@code +i} and {@code acc.y()} accumulates {@code -i} over the same range,
 * so their sum is always 0. That is the one thing the JUnit test for this class can assert without
 * depending on JIT warmup, tiering, or hardware - the allocation trend itself is a demonstration to
 * watch in the console output, not something to pin down as a hard assertion.
 */
public final class EscapeProbe {
  record Point(long x, long y) {
    Point plus(Point o) {
      return new Point(x + o.x, y + o.y);
    }
  }

  static long sum(int n) { // Point never escapes: C2 scalar-replaces it
    Point acc = new Point(0, 0);
    for (int i = 0; i < n; i++) acc = acc.plus(new Point(i, -i));
    return acc.x() + acc.y();
  }

  public static void main(String[] args) {
    var mx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    for (int round = 1; round <= 8; round++) {
      long before = mx.getCurrentThreadAllocatedBytes();
      long r = sum(1_000_000);
      long alloc = mx.getCurrentThreadAllocatedBytes() - before;
      System.out.printf("round %d: %,d bytes allocated (result %d)%n", round, alloc, r);
    }
    // Expected: ~32 MB in early rounds (interpreter/C1), then ~0 once C2 owns sum().
    // Extension: make Point escape (store it in a static field) and watch EA give up.
    // Deeper: -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining shows whether plus() was
    // inlined, which EA needs; a fastdebug build adds -XX:+PrintEscapeAnalysis.
  }
}
