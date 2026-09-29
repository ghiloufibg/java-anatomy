package dev.sevenrungs.jvminternals.footprint.estimate;

import dev.sevenrungs.jvminternals.footprint.ChildJvm;
import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.OrderGraph;

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
    String histogram = histogramText();
    System.out.println(BEGIN);
    System.out.println(histogram);
    System.out.println(END);
    sink = null;
  }

  /** Forks the probe under {@code config} holding {@code orders} orders; returns its histogram. */
  public static LiveHistogram capture(JvmMemoryConfig config, int orders) {
    String output = ChildJvm.run(config, "", HistogramProbe.class, String.valueOf(orders));
    int begin = output.indexOf(BEGIN);
    int end = output.indexOf(END);
    if (begin < 0 || end < begin) {
      throw new IllegalStateException("no histogram in probe output:\n" + output);
    }
    return LiveHistogram.parse(output.substring(begin + BEGIN.length(), end));
  }

  private static String histogramText() {
    // re-render the parsed rows rather than printing raw text, so the markers can't be confused
    // with a class name and the parent parses exactly what the child saw
    var rows = new StringBuilder();
    int i = 1;
    for (var e : LiveHistogram.ofThisJvm().entries()) {
      rows.append("%5d: %13d %14d  %s%n".formatted(i++, e.instances(), e.bytes(), e.className()));
    }
    return rows.toString();
  }
}
