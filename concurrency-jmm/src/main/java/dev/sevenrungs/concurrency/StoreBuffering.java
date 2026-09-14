package dev.sevenrungs.concurrency;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Store-buffering litmus test (JLS §17.4): can both threads read 0?
 *
 * <p>With plain/opaque fields: yes, on x86 you will see it within a few million runs. With
 * VarHandle release/acquire replaced by full volatile (sequentially consistent) access on both
 * fields, the outcome disappears entirely, because sequential consistency forbids the reordering
 * that store buffering exploits.
 *
 * <p>Run: {@code mvn -q -pl concurrency-jmm compile exec:java
 * -Dexec.mainClass=dev.sevenrungs.concurrency.StoreBuffering} Then: {@code ...
 * -Dexec.args=volatile}
 */
public final class StoreBuffering {
  static final VarHandle X, Y;

  static {
    try {
      var l = MethodHandles.lookup();
      X = l.findVarHandle(StoreBuffering.class, "x", int.class);
      Y = l.findVarHandle(StoreBuffering.class, "y", int.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  int x, y;

  public static void main(String[] args) throws InterruptedException {
    boolean sc = args.length > 0 && args[0].equals("volatile");
    long seenBothZero = runTrials(2_000_000, sc);
    // opaque: store buffers let BOTH reads see 0 (allowed by §17.4.5, no hb edge).
    // volatile: never. That gap is the entire reason the JMM exists.
    System.out.println("r1==0 && r2==0 observed " + seenBothZero + " times");
  }

  /**
   * Runs the litmus test {@code iterations} times and returns how many times both threads read 0.
   * Extracted from {@link #main} so tests can run a smaller trial count than the full demo.
   */
  static long runTrials(int iterations, boolean sequentiallyConsistent)
      throws InterruptedException {
    boolean sc = sequentiallyConsistent;
    long seenBothZero = 0;
    for (int run = 0; run < iterations; run++) {
      var s = new StoreBuffering();
      int[] r = new int[2];
      Thread t1 =
          Thread.ofPlatform()
              .start(
                  () -> {
                    if (sc) {
                      X.setVolatile(s, 1);
                      r[0] = (int) Y.getVolatile(s);
                    } else {
                      X.setOpaque(s, 1);
                      r[0] = (int) Y.getOpaque(s);
                    }
                  });
      Thread t2 =
          Thread.ofPlatform()
              .start(
                  () -> {
                    if (sc) {
                      Y.setVolatile(s, 1);
                      r[1] = (int) X.getVolatile(s);
                    } else {
                      Y.setOpaque(s, 1);
                      r[1] = (int) X.getOpaque(s);
                    }
                  });
      t1.join();
      t2.join();
      if (r[0] == 0 && r[1] == 0) seenBothZero++;
    }
    return seenBothZero;
  }
}
