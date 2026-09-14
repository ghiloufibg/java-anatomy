package dev.sevenrungs.phase2.exercise;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Exercise (phase 2): an allocation-heavy workload with three deliberately distinct shapes, meant
 * to be run under different collectors to compare their GC logs.
 *
 * <ul>
 *   <li>A stream of short-lived garbage - most of the allocation volume, dies in the young
 *       generation.
 *   <li>A bounded, sliding-window retained set - survives long enough to be promoted to old gen,
 *       giving the collector real old-gen pressure without ever growing the heap footprint
 *       unbounded.
 *   <li>Periodic humongous arrays, sized to exceed half of a typical (small-heap) G1 region - this
 *       is what triggers the "G1 Humongous Allocation" pause cause the artifact calls out.
 * </ul>
 *
 * <p>Bounded by tick count, not wall-clock time, so a small heap (see phase2/README.md for
 * recommended {@code -Xmx} per collector) reliably produces multiple GC cycles in a few seconds
 * regardless of machine speed.
 */
public final class AllocationWorkload {
  private static final int RETAINED_WINDOW = 20_000; // bounded old-gen pressure
  private static final int RETAINED_ITEM_BYTES = 256;
  private static final int HUMONGOUS_EVERY = 5_000;
  private static final int HUMONGOUS_BYTES = 1_500_000; // safely > half a small G1 region

  public static void main(String[] args) {
    int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 2_000_000;
    Deque<byte[]> retained = new ArrayDeque<>();
    long sink = 0; // keep the short-lived garbage from being optimized away entirely

    for (int i = 0; i < ticks; i++) {
      byte[] garbage = new byte[64 + (i % 192)]; // short-lived: most of the allocation volume
      sink += garbage.length;

      if (i % 4 == 0) { // steady old-gen pressure without an ever-growing heap
        retained.addLast(new byte[RETAINED_ITEM_BYTES]);
        if (retained.size() > RETAINED_WINDOW) retained.pollFirst();
      }

      if (i % HUMONGOUS_EVERY == 0) {
        byte[] humongous = new byte[HUMONGOUS_BYTES];
        sink += humongous.length; // dies immediately: allocate, then reclaim
      }
    }
    System.out.println("done: " + ticks + " ticks, sink=" + sink + ", retained=" + retained.size());
  }
}
