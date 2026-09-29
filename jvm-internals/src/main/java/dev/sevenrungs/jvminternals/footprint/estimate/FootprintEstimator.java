package dev.sevenrungs.jvminternals.footprint.estimate;

import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.LoadProfile;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate.ClassEstimate;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate.Kind;
import dev.sevenrungs.jvminternals.footprint.estimate.SizeOracle.ArrayLayout;
import dev.sevenrungs.jvminternals.footprint.estimate.SizeOracle.Layouts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Predicts what a <em>running application's</em> heap would weigh under each {@link
 * JvmMemoryConfig}, from one live class histogram - without restarting the application under every
 * flag set the way {@code FootprintComparison} does with its synthetic workload.
 *
 * <p>For every histogram row:
 *
 * <ul>
 *   <li><b>objects</b> - {@code bytes + instances x (size under target - size under source)}, both
 *       sizes from {@link SizeOracle}. Exact, including field re-packing.
 *   <li><b>arrays</b> - the histogram gives total bytes, not lengths, so the average length is
 *       recovered from the source layout and re-laid-out under the target, assuming alignment
 *       padding is uniformly distributed; the padding's full range is reported as uncertainty.
 *   <li><b>unresolved</b> (hidden classes, lambdas) - carried over unchanged, reported separately.
 * </ul>
 *
 * <p>Usage, against a real application:
 *
 * <pre>
 *   jcmd &lt;pid&gt; GC.class_histogram &gt; app.histo
 *   java -cp jvm-internals/target/classes:$JOL_JAR \
 *       dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimator \
 *       app.histo DEFAULT path/to/app.jar [app.jfr]
 * </pre>
 *
 * <p>The optional {@code app.jfr} is an {@link OldObjectRecording} of the same application: with
 * it, arrays are re-laid out from their sampled real lengths ({@link ArrayLengthSamples}) instead
 * of the uniform-padding estimate.
 *
 * <p>A whole-heap histogram also counts the JDK's own startup objects. Configurations that don't
 * match the JDK's default CDS archive ({@code -XX:-UseCompressedClassPointers}, {@code
 * -XX:ObjectAlignmentInBytes=16}) boot without it and so start from a different set of those; the
 * estimator predicts the layout change only, which is what matters once the application's own live
 * set dominates the heap.
 *
 * <p>With no arguments it runs on a {@link HistogramProbe} holding {@link LoadProfile#MEDIUM}'s
 * orders, minus an idle probe, so the prediction can be compared with {@code FootprintComparison}'s
 * measured table.
 */
public final class FootprintEstimator {
  /** Fewer sampled lengths than this and an array class falls back to the uniform estimate. */
  static final int MIN_ARRAY_SAMPLES = 10;

  private FootprintEstimator() {}

  public static void main(String[] args) throws IOException {
    LiveHistogram histogram;
    ArrayLengthSamples arrays;
    JvmMemoryConfig source;
    String classpath;
    if (args.length == 0 || args[0].isBlank()) {
      source = JvmMemoryConfig.DEFAULT;
      classpath = "";
      // minus an idle probe: the orders' own live set, without the JDK's startup objects
      var live = HistogramProbe.sampledLiveSet(source, LoadProfile.MEDIUM.orders());
      histogram = live.histogram();
      arrays = live.arrays();
      System.out.printf(
          "Histogram: HistogramProbe's live set for %,d orders (minus an idle probe), captured"
              + " under %s, array lengths sampled by JFR%n%n",
          LoadProfile.MEDIUM.orders(), source);
    } else {
      histogram = LiveHistogram.parse(Files.readString(Path.of(args[0])));
      source = args.length > 1 ? JvmMemoryConfig.valueOf(args[1]) : JvmMemoryConfig.DEFAULT;
      classpath = args.length > 2 ? args[2] : "";
      arrays =
          args.length > 3 ? ArrayLengthSamples.read(Path.of(args[3])) : ArrayLengthSamples.none();
      System.out.printf("Histogram: %s, captured under %s%n%n", args[0], source);
    }

    Map<JvmMemoryConfig, FootprintEstimate> estimates =
        estimateAll(histogram, arrays, source, classpath);
    System.out.println(
        "| Target configuration | Heap now | Predicted | Change | Objects (exact) | Arrays"
            + " | +/- | Unresolved |");
    System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|");
    for (FootprintEstimate e : estimates.values()) {
      System.out.printf(
          "| %s | %s | %s | %+.1f%% | %s | %s | %s | %s |%n",
          e.target().description(),
          mb(e.bytesBefore()),
          mb(e.bytesAfter()),
          e.changePercent(),
          signedMb(e.exactDelta()),
          signedMb(e.arrayDelta()),
          mb(e.uncertaintyBytes()),
          mb(e.unresolvedBytes()));
    }

    FootprintEstimate compact = estimates.get(JvmMemoryConfig.COMPACT_HEADERS);
    if (compact != null) {
      System.out.println("\nWhere compact headers save the most:\n");
      System.out.println("| Class | Instances | Now | Predicted | Change |");
      System.out.println("|---|---:|---:|---:|---:|");
      for (ClassEstimate c : compact.topMovers(10)) {
        System.out.printf(
            "| %s%s | %,d | %s | %s | %s |%n",
            c.className(),
            switch (c.kind()) {
              case ARRAY -> " (est.)";
              case SAMPLED_ARRAY -> " (sampled)";
              default -> "";
            },
            c.instances(),
            mb(c.bytesBefore()),
            mb(c.bytesAfter()),
            signedMb(c.delta()));
      }
    }
  }

  /** Estimates every configuration other than {@code source}; one oracle JVM per configuration. */
  public static Map<JvmMemoryConfig, FootprintEstimate> estimateAll(
      LiveHistogram histogram, JvmMemoryConfig source, String extraClasspath) {
    return estimateAll(histogram, ArrayLengthSamples.none(), source, extraClasspath);
  }

  /** As above, re-laying out arrays from sampled real lengths wherever there are enough samples. */
  public static Map<JvmMemoryConfig, FootprintEstimate> estimateAll(
      LiveHistogram histogram,
      ArrayLengthSamples arrays,
      JvmMemoryConfig source,
      String extraClasspath) {
    List<String> names = histogram.entries().stream().map(LiveHistogram.Entry::className).toList();
    Layouts sourceLayouts = SizeOracle.measure(source, names, extraClasspath);
    Map<JvmMemoryConfig, FootprintEstimate> estimates = new EnumMap<>(JvmMemoryConfig.class);
    for (JvmMemoryConfig target : JvmMemoryConfig.values()) {
      if (target != source) {
        Layouts targetLayouts = SizeOracle.measure(target, names, extraClasspath);
        estimates.put(target, estimate(histogram, arrays, sourceLayouts, targetLayouts));
      }
    }
    return estimates;
  }

  /** The pure part: no JVMs forked, just the two layout tables applied to the histogram. */
  public static FootprintEstimate estimate(
      LiveHistogram histogram, Layouts source, Layouts target) {
    return estimate(histogram, ArrayLengthSamples.none(), source, target);
  }

  /** The pure part, with sampled array lengths where available. */
  public static FootprintEstimate estimate(
      LiveHistogram histogram, ArrayLengthSamples arrays, Layouts source, Layouts target) {
    List<ClassEstimate> classes = new ArrayList<>();
    for (LiveHistogram.Entry e : histogram.entries()) {
      List<Integer> lengths = arrays.lengthsOf(e.className());
      classes.add(
          !e.isArray()
              ? estimateObject(
                  e,
                  source.objectSizes().get(e.className()),
                  target.objectSizes().get(e.className()))
              : lengths.size() >= MIN_ARRAY_SAMPLES
                  ? estimateSampledArray(e, lengths, source, target)
                  : estimateArray(e, source, target));
    }
    return new FootprintEstimate(source.config(), target.config(), classes);
  }

  private static ClassEstimate estimateObject(LiveHistogram.Entry e, Long sizeS, Long sizeT) {
    if (sizeS == null || sizeT == null) {
      return new ClassEstimate(
          e.className(), Kind.UNRESOLVED, e.instances(), e.bytes(), e.bytes(), 0);
    }
    // applied as a delta to the observed bytes, so variable-size instances (java.lang.Class
    // mirrors carry their static fields) keep their real size and only the layout change moves
    long after = e.bytes() + e.instances() * (sizeT - sizeS);
    return new ClassEstimate(e.className(), Kind.OBJECT, e.instances(), e.bytes(), after, 0);
  }

  private static ClassEstimate estimateArray(
      LiveHistogram.Entry e, Layouts source, Layouts target) {
    ArrayLayout s = source.arrays().get(e.className());
    ArrayLayout t = target.arrays().get(e.className());
    if (s == null || t == null || e.instances() == 0) {
      return new ClassEstimate(
          e.className(), Kind.UNRESOLVED, e.instances(), e.bytes(), e.bytes(), 0);
    }
    int alignS = source.alignment();
    int alignT = target.alignment();
    double averageBytes = e.bytes() / (double) e.instances();
    double averageLength =
        Math.max(0, (averageBytes - s.baseOffset() - expectedPadding(s, alignS)) / s.indexScale());
    double sizeT =
        Math.max(
            roundUp(t.baseOffset(), alignT),
            t.baseOffset() + averageLength * t.indexScale() + expectedPadding(t, alignT));
    long after = Math.round(e.instances() * sizeT);
    // padding is anywhere in [0, max] on either side; half the full range bounds the average error
    double perArray =
        maxPadding(s, alignS) / 2.0 * t.indexScale() / s.indexScale() + maxPadding(t, alignT) / 2.0;
    return new ClassEstimate(
        e.className(),
        Kind.ARRAY,
        e.instances(),
        e.bytes(),
        after,
        Math.round(e.instances() * perArray));
  }

  /**
   * Re-lays out each sampled length exactly under both layouts and scales the observed bytes by the
   * mean size ratio. JFR samples at TLAB refills, so an array is sampled roughly in proportion to
   * its size: a plain mean over samples is then a byte-weighted mean, which is what turns observed
   * bytes into predicted bytes. The uncertainty is two standard errors of that mean - zero when
   * every sampled array has the same length, as {@code ArrayList(3)}'s {@code Object[3]} all do.
   */
  private static ClassEstimate estimateSampledArray(
      LiveHistogram.Entry e, List<Integer> lengths, Layouts source, Layouts target) {
    ArrayLayout s = source.arrays().get(e.className());
    ArrayLayout t = target.arrays().get(e.className());
    if (s == null || t == null) {
      return estimateArray(e, source, target);
    }
    double[] ratios =
        lengths.stream()
            .mapToDouble(
                len ->
                    (double)
                            roundUp(
                                t.baseOffset() + (long) len * t.indexScale(), target.alignment())
                        / roundUp(s.baseOffset() + (long) len * s.indexScale(), source.alignment()))
            .toArray();
    double mean = java.util.Arrays.stream(ratios).average().orElseThrow();
    double variance =
        java.util.Arrays.stream(ratios).map(r -> (r - mean) * (r - mean)).sum()
            / Math.max(1, ratios.length - 1);
    double standardError = Math.sqrt(variance / ratios.length);
    return new ClassEstimate(
        e.className(),
        Kind.SAMPLED_ARRAY,
        e.instances(),
        e.bytes(),
        Math.round(e.bytes() * mean),
        Math.round(e.bytes() * 2 * standardError));
  }

  /**
   * Average padding for uniformly distributed lengths: the unpadded size moves in steps of {@code
   * gcd(scale, alignment)}, so padding is uniform over {@code 0, g, 2g, ... alignment - g}.
   */
  static double expectedPadding(ArrayLayout layout, int alignment) {
    return maxPadding(layout, alignment) / 2.0;
  }

  static int maxPadding(ArrayLayout layout, int alignment) {
    return alignment - gcd(layout.indexScale(), alignment);
  }

  private static int gcd(int a, int b) {
    return b == 0 ? a : gcd(b, a % b);
  }

  private static long roundUp(long value, int alignment) {
    return (value + alignment - 1) / alignment * alignment;
  }

  private static String mb(long bytes) {
    return "%.1f MB".formatted(bytes / 1_048_576.0);
  }

  private static String signedMb(long bytes) {
    return "%+.1f MB".formatted(bytes / 1_048_576.0);
  }
}
