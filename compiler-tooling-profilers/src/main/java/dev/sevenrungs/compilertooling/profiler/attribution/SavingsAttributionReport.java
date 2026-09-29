package dev.sevenrungs.compilertooling.profiler.attribution;

import dev.sevenrungs.compilertooling.profiler.AgentJar;
import dev.sevenrungs.compilertooling.profiler.attribution.AllocationAttribution.CallerSaving;
import dev.sevenrungs.compilertooling.profiler.attribution.AllocationAttribution.SiteSaving;
import dev.sevenrungs.compilertooling.profiler.attribution.AllocationAttribution.Unattributed;
import dev.sevenrungs.jvminternals.footprint.ChildJvm;
import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.LoadProfile;
import dev.sevenrungs.jvminternals.footprint.OrderGraph;
import dev.sevenrungs.jvminternals.footprint.estimate.ArrayLengthSamples;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimator;
import dev.sevenrungs.jvminternals.footprint.estimate.HistogramProbe;
import dev.sevenrungs.jvminternals.footprint.estimate.LiveHistogram;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * "Which of my code would each JVM flag set save (or cost) memory in?" - {@link
 * FootprintEstimator}'s per-class prediction, credited first to the allocation sites {@code
 * AllocationAgent} counted exactly, then - for what the agent can't see - to the application
 * callers JFR's allocation samples point at.
 *
 * <p>Against a real application, run it once under the agent and a JFR old-object recording, and
 * capture a histogram while it holds its steady-state live set:
 *
 * <pre>
 *   java -javaagent:profilers.jar=asm:com.acme. \
 *        -XX:FlightRecorderOptions:old-object-queue-size=100000 \
 *        -XX:StartFlightRecording:filename=app.jfr,jdk.OldObjectSample#enabled=true,\
 *   jdk.OldObjectSample#stackTrace=true,jdk.OldObjectSample#cutoff=0s \
 *        -jar app.jar &gt; agent.txt                             # agent report at exit
 *   jcmd &lt;pid&gt; GC.class_histogram &gt; app.histo            # while it runs
 *   java -cp ... SavingsAttributionReport app.histo agent.txt DEFAULT app.jar app.jfr com.acme.
 * </pre>
 *
 * <p>With no arguments it runs {@link HistogramProbe} under both, instrumenting {@link OrderGraph}
 * only - the same live set {@code FootprintEstimator} and {@code FootprintComparison} use - so
 * every number can be checked against theirs.
 */
public final class SavingsAttributionReport {
  static final String DEMO_PREFIX = OrderGraph.class.getName();

  private SavingsAttributionReport() {}

  /**
   * A live set, the agent's exact view of how it was allocated, and JFR's sampled views of who
   * caused the allocations the agent couldn't see and how long the live arrays are - all from the
   * same kind of run.
   */
  public record Capture(
      LiveHistogram liveSet,
      AllocationSites sites,
      JfrAllocationCallers callers,
      ArrayLengthSamples arrays) {}

  public static void main(String[] args) throws IOException {
    Capture capture;
    JvmMemoryConfig source = JvmMemoryConfig.DEFAULT;
    String classpath = "";
    if (args.length == 0 || args[0].isBlank()) {
      int orders = LoadProfile.MEDIUM.orders();
      capture = captureDemo(orders);
      System.out.printf(
          "HistogramProbe holding %,d orders under AllocationAgent (asm) and JFR allocation"
              + " sampling, application prefix %s%n%n",
          orders, DEMO_PREFIX);
    } else {
      source = args.length > 2 ? JvmMemoryConfig.valueOf(args[2]) : source;
      classpath = args.length > 3 ? args[3] : "";
      boolean recorded = args.length > 5;
      capture =
          new Capture(
              LiveHistogram.parse(Files.readString(Path.of(args[0]))),
              AllocationSites.parse(Files.readString(Path.of(args[1]))),
              recorded
                  ? JfrAllocationCallers.read(Path.of(args[4]), args[5])
                  : JfrAllocationCallers.none(),
              recorded ? ArrayLengthSamples.read(Path.of(args[4])) : ArrayLengthSamples.none());
    }

    print(attributeAll(capture, source, classpath));
  }

  /** Prints the per-configuration summary, then compact headers by site, caller and remainder. */
  public static void print(Map<JvmMemoryConfig, AllocationAttribution> byConfig) {
    System.out.println(
        "| Target configuration | Total change | Agent sites (exact) | Via JFR stacks (sampled)"
            + " | Unattributed |");
    System.out.println("|---|---:|---:|---:|---:|");
    for (AllocationAttribution a : byConfig.values()) {
      long total = a.attributedDelta() + a.callerDelta() + a.unattributedDelta();
      System.out.printf(
          "| %s | %s | %s | %s | %s |%n",
          a.target().description(),
          mb(total),
          withShare(a.attributedDelta(), total),
          withShare(a.callerDelta(), total),
          withShare(a.unattributedDelta(), total));
    }

    AllocationAttribution compact = byConfig.get(JvmMemoryConfig.COMPACT_HEADERS);
    if (compact != null) {
      System.out.println("\nCompact headers, by allocation site (agent, exact):\n");
      System.out.println("| Site | Allocates | Allocations | Change |");
      System.out.println("|---|---|---:|---:|");
      for (SiteSaving s : compact.sites().stream().limit(10).toList()) {
        System.out.printf(
            "| %s | %s | %,d | %s |%n",
            s.location(), s.className(), s.allocations(), mb(s.delta()));
      }
      System.out.println(
          "\nCompact headers, allocated inside the JDK, by calling method (JFR, sampled):\n");
      System.out.println("| Caller | Allocates | Sampled | Change |");
      System.out.println("|---|---|---:|---:|");
      for (CallerSaving c : compact.callers().stream().limit(10).toList()) {
        System.out.printf(
            "| %s | %s | %.1f MB | %s |%n",
            c.location(), c.className(), c.sampledBytes() / 1_048_576.0, mb(c.delta()));
      }
      System.out.println("\nCompact headers, still unattributed:\n");
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
        FootprintEstimator.estimateAll(capture.liveSet(), capture.arrays(), source, extraClasspath);
    estimates.forEach(
        (target, estimate) ->
            result.put(
                target,
                AllocationAttribution.attribute(estimate, capture.sites())
                    .withCallers(capture.callers())));
    return result;
  }

  /**
   * Runs {@link HistogramProbe} holding {@code orders} orders under {@code AllocationAgent} and a
   * JFR allocation-sampling recording, plus an idle probe under the same two to subtract - so the
   * live set is the orders' own, without the agent's or JFR's own objects.
   */
  public static Capture captureDemo(int orders) {
    try {
      Path recording = Files.createTempFile("allocations-", ".jfr");
      Path idleRecording = Files.createTempFile("allocations-idle-", ".jfr");
      try {
        String loaded = runProbe(orders, recording);
        String idle = runProbe(0, idleRecording);
        return new Capture(
            HistogramProbe.fromOutput(loaded).minus(HistogramProbe.fromOutput(idle)),
            AllocationSites.parse(loaded),
            JfrAllocationCallers.read(recording, DEMO_PREFIX)
                .minus(JfrAllocationCallers.read(idleRecording, DEMO_PREFIX)),
            ArrayLengthSamples.read(recording).minus(ArrayLengthSamples.read(idleRecording)));
      } finally {
        Files.deleteIfExists(recording);
        Files.deleteIfExists(idleRecording);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String runProbe(int orders, Path recording) {
    List<String> flags = new ArrayList<>();
    flags.add("-javaagent:" + AgentJar.build() + "=asm:" + DEMO_PREFIX);
    flags.addAll(JfrAllocationCallers.recordingFlags(recording));
    return ChildJvm.run(
        JvmMemoryConfig.DEFAULT, flags, "", HistogramProbe.class, String.valueOf(orders));
  }

  private static String withShare(long part, long total) {
    return "%s (%.0f%%)".formatted(mb(part), total == 0 ? 0 : 100.0 * part / total);
  }

  private static String mb(long bytes) {
    return "%+.1f MB".formatted(bytes / 1_048_576.0);
  }
}
