package dev.sevenrungs.compilertooling.profiler.attribution;

import dev.sevenrungs.jvminternals.footprint.OrderGraph;
import java.io.IOException;

/**
 * A stand-in for a service at steady state, for {@link LiveAttribution} to attach to: it holds
 * {@code args[0]} orders live and keeps replacing the oldest with a new one, the way sessions or
 * cache entries turn over. The live set's size stays constant while its contents are continuously
 * reallocated - which is what lets an agent attached <em>later</em> still see where it comes from.
 *
 * <p>Prints {@code READY <pid>} once the live set is built, and exits when its standard input is
 * closed (or after {@code args[1]} seconds, default 120, so a forgotten child never lingers).
 */
public final class ChurningOrders {
  /** Written to so the JIT can't prove the orders are dead. */
  static volatile Object sink;

  private ChurningOrders() {}

  public static void main(String[] args) throws InterruptedException {
    int size = Integer.parseInt(args[0]);
    long deadline =
        System.nanoTime() + (args.length > 1 ? Long.parseLong(args[1]) : 120) * 1_000_000_000L;
    OrderGraph.Order[] live = OrderGraph.build(size);
    sink = live;

    Thread stdinWatcher =
        Thread.ofPlatform()
            .daemon()
            .start(
                () -> {
                  try {
                    while (System.in.read() >= 0) {
                      // drain until the parent closes our stdin
                    }
                  } catch (IOException ignored) {
                    // treat a broken stdin like a closed one
                  }
                });
    System.out.println("READY " + ProcessHandle.current().pid());
    System.out.flush();

    long next = size;
    while (stdinWatcher.isAlive() && System.nanoTime() < deadline) {
      for (int i = 0; i < 10_000; i++, next++) {
        live[(int) (next % size)] = OrderGraph.order(next);
      }
      Thread.sleep(1); // a service, not a benchmark: leave the attaching tools some CPU
    }
  }
}
