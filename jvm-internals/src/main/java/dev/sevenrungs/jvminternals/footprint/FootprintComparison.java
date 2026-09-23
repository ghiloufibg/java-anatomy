package dev.sevenrungs.jvminternals.footprint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
  static final String HEAP = "-Xmx2g";

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
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add(HEAP);
    command.add("-XX:+UseSerialGC");
    // lets JOL self-attach for Instrumentation.getObjectSize - exact sizes under every header mode
    command.add("-Djdk.attach.allowAttachSelf=true");
    command.add("--add-opens");
    command.add("java.base/java.lang=ALL-UNNAMED");
    command.addAll(config.flags());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(FootprintProbe.class.getName());
    command.add(String.valueOf(orders));
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IllegalStateException("probe failed under " + config + ":\n" + output);
      }
      return output
          .lines()
          .filter(l -> l.startsWith(FootprintResult.PREFIX))
          .findFirst()
          .map(l -> FootprintResult.parse(config, l))
          .orElseThrow(
              () ->
                  new IllegalStateException("no FOOTPRINT line under " + config + ":\n" + output));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
