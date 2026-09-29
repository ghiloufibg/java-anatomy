package dev.sevenrungs.jvminternals.footprint.estimate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.LoadProfile;
import dev.sevenrungs.jvminternals.footprint.OrderGraph;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Prediction versus reality, per configuration: estimate every flag set from one histogram captured
 * under the defaults, then actually run the same live set under each flag set and compare (~15
 * child JVMs, ~20 s).
 *
 * <p>Both sides are "probe holding N orders" minus "idle probe" under the same configuration, so
 * the comparison is about the application's live set, not about the JDK startup objects that change
 * when a flag set boots without the default CDS archive (see {@link
 * LiveHistogram#minus(LiveHistogram)}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FootprintEstimatorTest {
  static final int ORDERS = LoadProfile.MEDIUM.scaled(0.1);

  private LiveHistogram source;
  private Map<JvmMemoryConfig, FootprintEstimate> predicted;
  private Map<JvmMemoryConfig, FootprintEstimate> sampled;
  private final Map<JvmMemoryConfig, LiveHistogram> actual = new EnumMap<>(JvmMemoryConfig.class);

  @BeforeAll
  @Timeout(300)
  void predictThenMeasureEveryConfiguration() {
    var live = HistogramProbe.sampledLiveSet(JvmMemoryConfig.DEFAULT, ORDERS);
    source = live.histogram();
    predicted = FootprintEstimator.estimateAll(source, JvmMemoryConfig.DEFAULT, "");
    sampled = FootprintEstimator.estimateAll(source, live.arrays(), JvmMemoryConfig.DEFAULT, "");
    for (JvmMemoryConfig target : predicted.keySet()) {
      actual.put(target, liveSetUnder(target));
    }
  }

  @Test
  void everyConfigurationOtherThanTheSourceIsEstimated() {
    assertEquals(JvmMemoryConfig.values().length - 1, predicted.size());
  }

  @ParameterizedTest
  @EnumSource(value = JvmMemoryConfig.class, mode = EnumSource.Mode.EXCLUDE, names = "DEFAULT")
  void theRealFootprintFallsInsideThePredictedBand(JvmMemoryConfig target) {
    FootprintEstimate e = predicted.get(target);
    long real = actual.get(target).totalBytes();
    long error = e.bytesAfter() - real;
    assertAll(
        () ->
            assertTrue(
                Math.abs(error) <= e.uncertaintyBytes(),
                () ->
                    "%s: predicted %,d B +/- %,d, measured %,d B"
                        .formatted(target, e.bytesAfter(), e.uncertaintyBytes(), real)),
        () ->
            assertTrue(
                Math.abs(error) <= 0.03 * real,
                () -> "%s: off by %.1f%%".formatted(target, 100.0 * error / real)));
  }

  @ParameterizedTest
  @EnumSource(value = JvmMemoryConfig.class, mode = EnumSource.Mode.EXCLUDE, names = "DEFAULT")
  void thePredictedDirectionMatchesTheMeasuredOne(JvmMemoryConfig target) {
    double predictedChange = predicted.get(target).changePercent();
    double measuredChange =
        100.0 * (actual.get(target).totalBytes() - source.totalBytes()) / source.totalBytes();
    assertEquals(
        Math.signum(measuredChange),
        Math.signum(predictedChange),
        "%s: predicted %+.1f%%, measured %+.1f%%"
            .formatted(target, predictedChange, measuredChange));
  }

  @ParameterizedTest
  @EnumSource(value = JvmMemoryConfig.class, mode = EnumSource.Mode.EXCLUDE, names = "DEFAULT")
  void plainObjectsArePredictedToTheByte(JvmMemoryConfig target) {
    // the order graph's own classes have exactly ORDERS and 3 x ORDERS instances in both runs, so
    // the oracle's layout, not an approximation, is all that decides their predicted bytes
    for (Class<?> c : List.of(OrderGraph.Order.class, OrderGraph.LineItem.class)) {
      String name = c.getName();
      long predictedBytes =
          predicted.get(target).classes().stream()
              .filter(ce -> ce.className().equals(name))
              .findFirst()
              .orElseThrow()
              .bytesAfter();
      long realBytes =
          actual.get(target).entries().stream()
              .filter(en -> en.className().equals(name))
              .findFirst()
              .orElseThrow()
              .bytes();
      assertEquals(realBytes, predictedBytes, target + " " + name);
    }
  }

  @Test
  void compactHeadersSaveOnTheSameClassesTheComparisonMeasured() {
    // the same per-class savings JOL showed for OrderGraph: 9 of 14 objects per order shrink
    var movers =
        predicted.get(JvmMemoryConfig.COMPACT_HEADERS).topMovers(5).stream()
            .map(FootprintEstimate.ClassEstimate::className)
            .toList();
    assertTrue(movers.contains(OrderGraph.LineItem.class.getName()), movers.toString());
    assertTrue(movers.contains("java.util.HashMap$Node"), movers.toString());
  }

  @Test
  void theOrdersArraysAreReLaidOutFromSampledLengths() {
    var kinds =
        sampled.get(JvmMemoryConfig.COMPACT_HEADERS).classes().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    FootprintEstimate.ClassEstimate::className,
                    FootprintEstimate.ClassEstimate::kind));
    for (String array : List.of("[B", "[Ljava.lang.Object;", "[Ljava.util.HashMap$Node;")) {
      assertEquals(FootprintEstimate.Kind.SAMPLED_ARRAY, kinds.get(array), array);
    }
  }

  @ParameterizedTest
  @EnumSource(value = JvmMemoryConfig.class, mode = EnumSource.Mode.EXCLUDE, names = "DEFAULT")
  void sampledArrayLengthsPredictWithinOnePercent(JvmMemoryConfig target) {
    // measured over 12 runs (3 loads x 4 configs): at most 0.42% off, against 2.3% for the
    // uniform-padding estimate. The band covers only the array-length sampling; the half percent
    // on top is the reference measurement's own noise - the JDK objects that still differ
    // between configurations after subtracting an idle probe, largest on small live sets.
    FootprintEstimate e = sampled.get(target);
    long real = actual.get(target).totalBytes();
    long error = e.bytesAfter() - real;
    assertAll(
        () ->
            assertTrue(
                Math.abs(error) <= e.uncertaintyBytes() + 0.005 * real,
                () ->
                    "%s: predicted %,d B +/- %,d, measured %,d B"
                        .formatted(target, e.bytesAfter(), e.uncertaintyBytes(), real)),
        () ->
            assertTrue(
                Math.abs(error) <= 0.01 * real,
                () -> "%s: off by %.2f%%".formatted(target, 100.0 * error / real)));
  }

  @Test
  void sampledLengthsBeatTheUniformAssumptionWhereItIsWorst() {
    // 16-byte alignment: padding reaches 15 bytes, and this workload's arrays all have the same
    // few short lengths - nothing like uniformly spread
    long real = actual.get(JvmMemoryConfig.ALIGNMENT_16).totalBytes();
    long uniformError = Math.abs(predicted.get(JvmMemoryConfig.ALIGNMENT_16).bytesAfter() - real);
    long sampledError = Math.abs(sampled.get(JvmMemoryConfig.ALIGNMENT_16).bytesAfter() - real);
    assertTrue(
        sampledError < uniformError,
        "sampled off by %,d B, uniform by %,d B".formatted(sampledError, uniformError));
  }

  private static LiveHistogram liveSetUnder(JvmMemoryConfig config) {
    return HistogramProbe.capture(config, ORDERS).minus(HistogramProbe.capture(config, 0));
  }
}
