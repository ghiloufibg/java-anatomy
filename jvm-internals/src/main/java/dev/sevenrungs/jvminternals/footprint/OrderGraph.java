package dev.sevenrungs.jvminternals.footprint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately ordinary business object graph: the kind of many-small-objects heap where header
 * size, not payload, decides the footprint.
 *
 * <p>One {@link Order} built by {@link #order(long)} is 14 heap objects - the order, a boxed {@code
 * Long}, a {@code String} and its {@code byte[]}, an {@code Instant}, an {@code ArrayList} and its
 * {@code Object[]}, three {@link LineItem}s, a {@code HashMap}, its {@code Node[]} table and two
 * {@code HashMap.Node}s - carrying only ~60 bytes of actual field data. That ratio is what makes
 * typical enterprise heaps so sensitive to header and reference size.
 */
public final class OrderGraph {
  /** Objects allocated per order, excluding the shared pools below. */
  public static final int OBJECTS_PER_ORDER = 14;

  public enum Status {
    NEW,
    PAID,
    SHIPPED
  }

  public static final class LineItem {
    final int quantity;
    final long priceCents;
    final String productCode;

    LineItem(int quantity, long priceCents, String productCode) {
      this.quantity = quantity;
      this.priceCents = priceCents;
      this.productCode = productCode;
    }
  }

  public static final class Order {
    final long id;
    final Status status;
    final Long customerId;
    final String reference;
    final Instant createdAt;
    final List<LineItem> lines;
    final Map<String, String> attributes;

    Order(
        long id,
        Status status,
        Long customerId,
        String reference,
        Instant createdAt,
        List<LineItem> lines,
        Map<String, String> attributes) {
      this.id = id;
      this.status = status;
      this.customerId = customerId;
      this.reference = reference;
      this.createdAt = createdAt;
      this.lines = lines;
      this.attributes = attributes;
    }
  }

  // shared across all orders, so they amortize to ~0 bytes per order - like interned catalog data
  private static final String[] PRODUCT_CODES = new String[64];
  private static final String[] CHANNELS = {"web", "mobile", "store"};
  private static final String[] REGIONS = {"eu-west", "us-east", "ap-south"};
  private static final Status[] STATUSES = Status.values();
  private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

  static {
    for (int i = 0; i < PRODUCT_CODES.length; i++) {
      PRODUCT_CODES[i] = "PRD-" + (1000 + i);
    }
  }

  private OrderGraph() {}

  /**
   * Builds one order; deterministic in {@code id} so every JVM configuration builds the same heap.
   */
  public static Order order(long id) {
    var lines = new ArrayList<LineItem>(3);
    for (int i = 0; i < 3; i++) {
      lines.add(
          new LineItem(
              1 + (int) ((id + i) % 5),
              199 + (id * 7 + i) % 10_000,
              PRODUCT_CODES[(int) ((id + i) % PRODUCT_CODES.length)]));
    }
    var attributes = new HashMap<String, String>(4);
    attributes.put("channel", CHANNELS[(int) (id % CHANNELS.length)]);
    attributes.put("region", REGIONS[(int) (id % REGIONS.length)]);
    return new Order(
        id,
        STATUSES[(int) (id % STATUSES.length)],
        Long.valueOf(1_000_000L + id), // outside the Long cache, so a real box per order
        "ORD-" + id,
        EPOCH.plusSeconds(id),
        lines,
        attributes);
  }

  /** Builds {@code count} orders and keeps them strongly reachable from the returned array. */
  public static Order[] build(int count) {
    var orders = new Order[count];
    for (int i = 0; i < count; i++) {
      orders[i] = order(i);
    }
    return orders;
  }
}
