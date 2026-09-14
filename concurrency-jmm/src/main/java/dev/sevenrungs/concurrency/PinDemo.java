package dev.sevenrungs.concurrency;

/**
 * Exercise (phase 1): isolates the one shape of code that used to pin a virtual thread's carrier
 * before JDK 24.
 *
 * <p>Before JEP 491 (JDK 24), a virtual thread that entered a {@code synchronized} block and then
 * performed a blocking operation <em>while still holding the monitor</em> could not be unmounted:
 * HotSpot's freeze/thaw machinery did not know how to carry a held monitor across an unmount, so
 * the platform carrier thread stayed pinned for the duration of the block — exactly the situation
 * {@link WriterPreferringReadWriteLock}'s own internals avoid, because they block only via {@code
 * LockSupport.park} (through AQS), never inside a {@code synchronized} block.
 *
 * <p>JEP 491 taught the JVM to unmount even with lightweight-locked monitors held, so on the JDK 25
 * this reactor targets, the exact pattern below no longer pins. {@code PinDemoTest} proves that
 * with a live {@code jdk.VirtualThreadPinned} JFR subscription rather than trusting the JEP.
 */
public final class PinDemo {
  private static final Object LOCK = new Object();

  /** The pattern: synchronized, then a blocking call, without releasing the monitor first. */
  public static void blockWhileSynchronized() throws InterruptedException {
    synchronized (LOCK) {
      Thread.sleep(50); // pre-JDK 24: pins the carrier for these 50ms. JDK 24+: does not.
    }
  }

  public static void main(String[] args) throws Exception {
    Thread vthread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    blockWhileSynchronized();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
    vthread.join();
    System.out.println("done - see PinDemoTest for the JFR-verified JEP 491 guarantee");
  }
}
