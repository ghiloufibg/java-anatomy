package dev.sevenrungs.jvminternals.footprint;

/**
 * How big a live set the probe retains: the "normal / medium / high load" knob.
 *
 * <p>The counts are orders held live at once, not requests per second - heap footprint is a
 * function of what survives, not of throughput. At this JDK's defaults one order graph is ~444
 * bytes, so these land at roughly 21 MB, 108 MB and 436 MB of live heap: a small service, a busy
 * one, and a cache-heavy one. {@link #scaled(double)} shrinks them for fast tests without changing
 * the object graph's shape, which is what the per-object header savings depend on.
 */
public enum LoadProfile {
  NORMAL(50_000),
  MEDIUM(250_000),
  HIGH(1_000_000);

  private final int orders;

  LoadProfile(int orders) {
    this.orders = orders;
  }

  public int orders() {
    return orders;
  }

  /** The order count scaled by {@code factor}, never below 1,000 (the JOL sample size). */
  public int scaled(double factor) {
    return Math.max(1_000, (int) Math.round(orders * factor));
  }
}
