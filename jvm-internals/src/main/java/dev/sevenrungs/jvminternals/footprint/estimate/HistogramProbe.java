package dev.sevenrungs.jvminternals.footprint.estimate;

import dev.sevenrungs.jvminternals.footprint.ChildJvm;
import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.OrderGraph;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Child-JVM stand-in for "your application": holds {@code args[0]} orders live and prints its own
 * class histogram between {@link #BEGIN} and {@link #END} markers.
 *
 * <p>Captured under the default flags, it's the estimator's input; captured under each other {@link
 * JvmMemoryConfig}, it's the ground truth the estimate is checked against.
 */
public final class HistogramProbe {
  static final String BEGIN = "HISTOGRAM-BEGIN";
  static final String END = "HISTOGRAM-END";

  /** Written to so the live set stays reachable while the histogram is taken. */
  static volatile Object sink;

  private HistogramProbe() {}

  public static void main(String[] args) {
    sink = OrderGraph.build(Integer.parseInt(args[0]));
    // rendered from the parsed rows, not printed raw, so the parent parses exactly what the child
    // saw and the markers can't be confused with a class name
    String histogram = LiveHistogram.ofThisJvm().toText();
    System.out.println(BEGIN);
    System.out.println(histogram);
    System.out.println(END);
    // collect the histogram's own garbage while the orders are still referenced, so a JFR
    // old-object recording dumped at exit (jdk.OldObjectSample) is left with survivors only
    System.gc();
    sink = null;
  }

  /** Forks the probe under {@code config} holding {@code orders} orders; returns its histogram. */
  public static LiveHistogram capture(JvmMemoryConfig config, int orders) {
    return fromOutput(ChildJvm.run(config, "", HistogramProbe.class, String.valueOf(orders)));
  }

  /** An application live set, and the lengths of its arrays sampled by JFR in the same runs. */
  public record SampledLiveSet(LiveHistogram histogram, ArrayLengthSamples arrays) {}

  /**
   * The orders' own live set under {@code config} - a probe holding {@code orders} orders minus an
   * idle probe - with each run recorded by {@link OldObjectRecording} and the idle run's array
   * samples subtracted too.
   */
  public static SampledLiveSet sampledLiveSet(JvmMemoryConfig config, int orders) {
    try {
      Path loaded = Files.createTempFile("probe-", ".jfr");
      Path idle = Files.createTempFile("probe-idle-", ".jfr");
      try {
        LiveHistogram loadedHistogram = fromOutput(runRecorded(config, orders, loaded));
        LiveHistogram idleHistogram = fromOutput(runRecorded(config, 0, idle));
        return new SampledLiveSet(
            loadedHistogram.minus(idleHistogram),
            ArrayLengthSamples.read(loaded).minus(ArrayLengthSamples.read(idle)));
      } finally {
        Files.deleteIfExists(loaded);
        Files.deleteIfExists(idle);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String runRecorded(JvmMemoryConfig config, int orders, Path recording) {
    return ChildJvm.run(
        config,
        OldObjectRecording.flags(recording),
        "",
        HistogramProbe.class,
        String.valueOf(orders));
  }

  /**
   * The histogram a probe printed, from its full output - which may carry other lines around it,
   * such as a {@code -javaagent}'s own report printed at shutdown.
   */
  public static LiveHistogram fromOutput(String output) {
    int begin = output.indexOf(BEGIN);
    int end = output.indexOf(END);
    if (begin < 0 || end < begin) {
      throw new IllegalStateException("no histogram in probe output:\n" + output);
    }
    return LiveHistogram.parse(output.substring(begin + BEGIN.length(), end));
  }
}
