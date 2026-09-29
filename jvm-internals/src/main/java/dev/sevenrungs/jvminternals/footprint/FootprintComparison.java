package dev.sevenrungs.jvminternals.footprint;

import java.util.EnumMap;
import java.util.Map;

/**
 * Forks one {@link FootprintProbe} JVM per {@link JvmMemoryConfig} per {@link LoadProfile} and
 * prints how much live heap each flag set saves (or costs) against the JVM's defaults.
 *
 * <p>Object layout flags are fixed at JVM launch - there is no API to flip compressed oops or
 * compact headers in a running process - so the only honest comparison is separate JVMs building
 * the identical object graph. Every child gets the same collector ({@code -XX:+UseSerialGC}, whose
 * post-GC "used" is exactly the compacted bytes) and the same {@code -Xmx}, so the flag under test
 * is the only variable.
 *
 * <p>{@code args[0]}, optional, scales every load profile's order count (default {@code 1.0}):
 *
 * <pre>
 *   mvn -q -pl jvm-internals compile exec:exec \
 *       -Dexec.mainClass=dev.sevenrungs.jvminternals.footprint.FootprintComparison \
 *       -Dexec.programArgs=0.2
 * </pre>
 */
public final class FootprintComparison {
  private FootprintComparison() {}

  public static void main(String[] args) {
    double scale = args.length > 0 && !args[0].isBlank() ? Double.parseDouble(args[0]) : 1.0;
    System.out.println(
        "| Load | Orders | JVM configuration | Header | Ref | Align | Bytes/order (JOL)"
            + " | Live heap | vs default |");
    System.out.println("|---|---:|---|---:|---:|---:|---:|---:|---:|");
    for (LoadProfile load : LoadProfile.values()) {
      Map<JvmMemoryConfig, FootprintResult> results = measureAll(load.scaled(scale));
      FootprintResult baseline = results.get(JvmMemoryConfig.DEFAULT);
      for (FootprintResult r : results.values()) {
        System.out.printf(
            "| %s | %,d | %s | %d B | %d B | %d B | %.1f | %.1f MB | %s |%n",
            load,
            r.orders(),
            r.config().description(),
            r.headerBytes(),
            r.referenceBytes(),
            r.alignmentBytes(),
            r.jolBytesPerOrder(),
            r.liveHeapBytes() / 1_048_576.0,
            r == baseline
                ? "baseline"
                : String.format(
                    "%+.1f%% (%+.1f MB)",
                    r.heapChangePercent(baseline),
                    (r.liveHeapBytes() - baseline.liveHeapBytes()) / 1_048_576.0));
      }
    }
  }

  /** Measures {@code orders} under every configuration, in declaration order. */
  public static Map<JvmMemoryConfig, FootprintResult> measureAll(int orders) {
    Map<JvmMemoryConfig, FootprintResult> results = new EnumMap<>(JvmMemoryConfig.class);
    for (JvmMemoryConfig config : JvmMemoryConfig.values()) {
      results.put(config, measure(config, orders));
    }
    return results;
  }

  /** Forks a child JVM with {@code config}'s flags and parses its footprint report. */
  public static FootprintResult measure(JvmMemoryConfig config, int orders) {
    String output = ChildJvm.run(config, "", FootprintProbe.class, String.valueOf(orders));
    return FootprintResult.parse(config, ChildJvm.lineStartingWith(output, FootprintResult.PREFIX));
  }
}
