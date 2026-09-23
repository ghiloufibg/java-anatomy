package dev.sevenrungs.jvminternals.footprint;

import java.util.HashMap;
import java.util.Map;

/** One {@link FootprintProbe} run, parsed back from its {@code FOOTPRINT key=value ...} line. */
public record FootprintResult(
    JvmMemoryConfig config,
    int orders,
    int headerBytes,
    int referenceBytes,
    int alignmentBytes,
    double jolBytesPerOrder,
    long liveHeapBytes) {

  static final String PREFIX = "FOOTPRINT ";

  static FootprintResult parse(JvmMemoryConfig config, String line) {
    if (!line.startsWith(PREFIX)) {
      throw new IllegalArgumentException("not a FOOTPRINT line: " + line);
    }
    Map<String, String> fields = new HashMap<>();
    for (String pair : line.substring(PREFIX.length()).trim().split("\\s+")) {
      int eq = pair.indexOf('=');
      fields.put(pair.substring(0, eq), pair.substring(eq + 1));
    }
    return new FootprintResult(
        config,
        Integer.parseInt(fields.get("orders")),
        Integer.parseInt(fields.get("headerBytes")),
        Integer.parseInt(fields.get("referenceBytes")),
        Integer.parseInt(fields.get("alignmentBytes")),
        Double.parseDouble(fields.get("jolBytesPerOrder")),
        Long.parseLong(fields.get("liveHeapBytes")));
  }

  /** Live heap per order, including the order's slot in the holding array. */
  public double heapBytesPerOrder() {
    return liveHeapBytes / (double) orders;
  }

  /** Percent change of this run's live heap relative to {@code baseline}; negative = saved. */
  public double heapChangePercent(FootprintResult baseline) {
    return 100.0 * (liveHeapBytes - baseline.liveHeapBytes) / baseline.liveHeapBytes;
  }

  /** Percent change of JOL's exact per-order size relative to {@code baseline}. */
  public double jolChangePercent(FootprintResult baseline) {
    return 100.0 * (jolBytesPerOrder - baseline.jolBytesPerOrder) / baseline.jolBytesPerOrder;
  }
}
