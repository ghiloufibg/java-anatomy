package dev.sevenrungs.jvminternals.footprint;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

/**
 * The child-JVM half of the footprint comparison: builds {@code args[0]} orders, holds them live,
 * and prints one {@code FOOTPRINT key=value ...} line describing what they cost under whatever JVM
 * flags this process was launched with.
 *
 * <p>Two independent measurements, so each can check the other:
 *
 * <ul>
 *   <li><b>{@code jolBytesPerOrder}</b> - exact: JOL walks a 1,000-order sample and sums every
 *       reachable object's real size under this VM's layout. Shared pools amortize to ~0.
 *   <li><b>{@code liveHeapBytes}</b> - empirical: used heap after full GCs, with and without the
 *       orders retained. This is what a dashboard would show you. Run it under {@code
 *       -XX:+UseSerialGC} for a stable number: Serial's "used" is exactly the compacted bytes.
 * </ul>
 */
public final class FootprintProbe {
  static final int JOL_SAMPLE = 1_000;

  /** One {@code int} field: its offset is exactly the object header size under this VM. */
  static final class HeaderProbe {
    int firstField;
  }

  /** Written to (never read) so the JIT can't prove the live set is dead. */
  static volatile Object sink;

  private FootprintProbe() {}

  public static void main(String[] args) throws Exception {
    int orders = Integer.parseInt(args[0]);

    long before = usedHeapAfterGc();
    OrderGraph.Order[] live = OrderGraph.build(orders);
    sink = live;
    long after = usedHeapAfterGc();

    double jolBytesPerOrder =
        GraphLayout.parseInstance((Object[]) Arrays.copyOf(live, JOL_SAMPLE)).totalSize()
            / (double) JOL_SAMPLE;

    var vm = VM.current();
    int header =
        (int) vm.fieldOffset(HeaderProbe.class.getDeclaredField("firstField")); // 8, 12 or 16
    int reference = vm.arrayIndexScale("java.lang.Object"); // 4 = compressed oops
    System.out.printf(
        "FOOTPRINT orders=%d headerBytes=%d referenceBytes=%d alignmentBytes=%d"
            + " jolBytesPerOrder=%.2f liveHeapBytes=%d%n",
        orders, header, reference, vm.objectAlignment(), jolBytesPerOrder, after - before);
    sink = live.length; // keep `live` reachable until after the second measurement
  }

  /** Used heap after enough full GCs that the number stops moving. */
  static long usedHeapAfterGc() throws InterruptedException {
    var memory = ManagementFactory.getMemoryMXBean();
    long previous = Long.MAX_VALUE;
    for (int i = 0; i < 5; i++) {
      System.gc();
      Thread.sleep(20);
      long used = memory.getHeapMemoryUsage().getUsed();
      if (used == previous) {
        return used;
      }
      previous = used;
    }
    return previous;
  }
}
