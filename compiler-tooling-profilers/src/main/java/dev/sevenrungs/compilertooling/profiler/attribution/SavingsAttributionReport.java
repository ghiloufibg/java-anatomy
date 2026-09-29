package dev.sevenrungs.compilertooling.profiler.attribution;

import dev.sevenrungs.compilertooling.profiler.AgentJar;
import dev.sevenrungs.compilertooling.profiler.attribution.AllocationAttribution.SiteSaving;
import dev.sevenrungs.compilertooling.profiler.attribution.AllocationAttribution.Unattributed;
import dev.sevenrungs.jvminternals.footprint.ChildJvm;
import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.LoadProfile;
import dev.sevenrungs.jvminternals.footprint.OrderGraph;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimator;
import dev.sevenrungs.jvminternals.footprint.estimate.HistogramProbe;
import dev.sevenrungs.jvminternals.footprint.estimate.LiveHistogram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * "Which of my code would each JVM flag set save (or cost) memory in?" - {@link
 * FootprintEstimator}'s per-class prediction, credited to the allocation sites {@code
 * AllocationAgent} counted.
 *
 * <p>Against a real application, run it once under the agent and capture a histogram:
 *
 * <pre>
 *   java -javaagent:profilers.jar=asm:com.acme. -jar app.jar &gt; agent.txt   # report at exit
 *   jcmd &lt;pid&gt; GC.class_histogram &gt; app.histo                            # while it runs
 *   java -cp ... SavingsAttributionReport app.histo agent.txt DEFAULT path/to/app.jar
 * </pre>
 *
 * <p>With no arguments it runs {@link HistogramProbe} under the agent, instrumenting {@link
 * OrderGraph} only - the same live set {@code FootprintEstimator} and {@code FootprintComparison}
 * use - so every number can be checked against theirs.
 */
public final class SavingsAttributionReport {
  static final String DEMO_PREFIX = OrderGraph.class.getName();

  private SavingsAttributionReport() {}

  /** A live set and the agent's view of how it was allocated, from the same kind of run. */
  public record Capture(LiveHistogram liveSet, AllocationSites sites) {}

  public static void main(String[] args) throws IOException {
    Capture capture;
    JvmMemoryConfig source = JvmMemoryConfig.DEFAULT;
    String classpath = "";
    if (args.length == 0 || args[0].isBlank()) {
      int orders = LoadProfile.MEDIUM.orders();
      capture = captureDemo(orders);
      System.out.printf(
          "HistogramProbe holding %,d orders under AllocationAgent (asm, prefix %s)%n%n",
          orders, DEMO_PREFIX);
    } else {
      capture =
          new Capture(
              LiveHistogram.parse(Files.readString(Path.of(args[0]))),
              AllocationSites.parse(Files.readString(Path.of(args[1]))));
      source = args.length > 2 ? JvmMemoryConfig.valueOf(args[2]) : source;
      classpath = args.length > 3 ? args[3] : "";
    }

    Map<JvmMemoryConfig, AllocationAttribution> byConfig = attributeAll(capture, source, classpath);
    System.out.println(
        "| Target configuration | Total change | In your code | Allocated elsewhere |");
    System.out.println("|---|---:|---:|---:|");
    for (AllocationAttribution a : byConfig.values()) {
      long total = a.attributedDelta() + a.unattributedDelta();
      System.out.printf(
          "| %s | %s | %s (%.0f%%) | %s |%n",
          a.target().description(),
          mb(total),
          mb(a.attributedDelta()),
          total == 0 ? 0 : 100.0 * a.attributedDelta() / total,
          mb(a.unattributedDelta()));
    }

    AllocationAttribution compact = byConfig.get(JvmMemoryConfig.COMPACT_HEADERS);
    if (compact != null) {
      System.out.println("\nCompact headers, by allocation site:\n");
      System.out.println("| Site | Allocates | Allocations | Change |");
      System.out.println("|---|---|---:|---:|");
      for (SiteSaving s : compact.sites().stream().limit(10).toList()) {
        System.out.printf(
            "| %s | %s | %,d | %s |%n",
            s.location(), s.className(), s.allocations(), mb(s.delta()));
      }
      System.out.println("\nCompact headers, allocated where the agent doesn't look:\n");
      System.out.println("| Class | Live | Seen by agent | Change |");
      System.out.println("|---|---:|---:|---:|");
      for (Unattributed u : compact.unattributed().stream().limit(10).toList()) {
        System.out.printf(
            "| %s | %,d | %,d | %s |%n",
            u.className(), u.liveInstances(), u.seenAllocations(), mb(u.delta()));
      }
    }
  }

  /** Estimates every configuration from {@code capture} and attributes each one. */
  public static Map<JvmMemoryConfig, AllocationAttribution> attributeAll(
      Capture capture, JvmMemoryConfig source, String extraClasspath) {
    Map<JvmMemoryConfig, AllocationAttribution> result = new EnumMap<>(JvmMemoryConfig.class);
    Map<JvmMemoryConfig, FootprintEstimate> estimates =
        FootprintEstimator.estimateAll(capture.liveSet(), source, extraClasspath);
    estimates.forEach(
        (target, estimate) ->
            result.put(target, AllocationAttribution.attribute(estimate, capture.sites())));
    return result;
  }

  /**
   * Runs {@link HistogramProbe} holding {@code orders} orders under {@code AllocationAgent}, plus
   * an idle probe under the same agent to subtract, so the live set is the orders' own.
   */
  public static Capture captureDemo(int orders) {
    String loaded = runProbeUnderAgent(orders);
    String idle = runProbeUnderAgent(0);
    return new Capture(
        HistogramProbe.fromOutput(loaded).minus(HistogramProbe.fromOutput(idle)),
        AllocationSites.parse(loaded));
  }

  private static String runProbeUnderAgent(int orders) {
    String agent = "-javaagent:" + AgentJar.build() + "=asm:" + DEMO_PREFIX;
    return ChildJvm.run(
        JvmMemoryConfig.DEFAULT, List.of(agent), "", HistogramProbe.class, String.valueOf(orders));
  }

  private static String mb(long bytes) {
    return "%+.1f MB".formatted(bytes / 1_048_576.0);
  }
}
