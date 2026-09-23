package dev.sevenrungs.jvminternals.footprint;

import static dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig.ALIGNMENT_16;
import static dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig.COMPACT_HEADERS;
import static dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig.DEFAULT;
import static dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig.NO_COMPRESSED_CLASS_POINTERS;
import static dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig.NO_COMPRESSED_OOPS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Forks a real child JVM per {@link JvmMemoryConfig} per {@link LoadProfile} (15 JVMs, ~20s) and
 * asserts the savings each flag set buys - or costs - on the same object graph.
 *
 * <p>Loads are scaled to a tenth of {@link LoadProfile}'s production-sized counts to keep {@code
 * mvn verify} fast; per-object costs don't depend on the count, which {@link
 * #perOrderCostIsIndependentOfLoadSoSavingsScaleLinearly()} checks directly. Run {@link
 * FootprintComparison} at full scale for the real 21 / 108 / 436 MB numbers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FootprintComparisonTest {
  static final double TEST_SCALE = 0.1;

  private final Map<LoadProfile, Map<JvmMemoryConfig, FootprintResult>> byLoad =
      new EnumMap<>(LoadProfile.class);

  @BeforeAll
  @Timeout(300)
  void measureEveryConfigurationAtEveryLoad() {
    for (LoadProfile load : LoadProfile.values()) {
      byLoad.put(load, FootprintComparison.measureAll(load.scaled(TEST_SCALE)));
    }
  }

  @ParameterizedTest
  @EnumSource(LoadProfile.class)
  void eachFlagSetProducesTheObjectShapeItPromises(LoadProfile load) {
    var r = byLoad.get(load);
    assertAll(
        () -> assertEquals(12, r.get(DEFAULT).headerBytes(), "mark word + compressed klass"),
        () -> assertEquals(4, r.get(DEFAULT).referenceBytes(), "compressed oops"),
        () -> assertEquals(8, r.get(COMPACT_HEADERS).headerBytes(), "JEP 519: klass in mark"),
        () -> assertEquals(16, r.get(NO_COMPRESSED_CLASS_POINTERS).headerBytes()),
        () -> assertEquals(8, r.get(NO_COMPRESSED_OOPS).referenceBytes()),
        () -> assertEquals(16, r.get(ALIGNMENT_16).alignmentBytes()));
  }

  @ParameterizedTest
  @EnumSource(LoadProfile.class)
  void compactHeadersSaveAtLeastTenPercentOfLiveHeap(LoadProfile load) {
    var r = byLoad.get(load);
    FootprintResult baseline = r.get(DEFAULT);
    FootprintResult compact = r.get(COMPACT_HEADERS);

    // 14 objects/order x 4 header bytes = 56 bytes before alignment; measured on JDK 25 it's
    // ~444 -> ~372 bytes/order, about -16%. Assert a conservative floor, not the exact figure.
    assertTrue(
        compact.jolChangePercent(baseline) <= -10,
        () -> "compact headers should save >= 10% per order, got " + describe(compact, baseline));
    assertTrue(
        compact.heapChangePercent(baseline) <= -10,
        () ->
            "compact headers should save >= 10% of live heap, got " + describe(compact, baseline));
  }

  @ParameterizedTest
  @EnumSource(LoadProfile.class)
  void everyOtherFlagSetCostsMemoryOnThisGraph(LoadProfile load) {
    var r = byLoad.get(load);
    FootprintResult baseline = r.get(DEFAULT);
    assertAll(
        // 8-byte refs: the implicit price of crossing -Xmx 32g. Every reference field doubles.
        () ->
            assertTrue(
                r.get(NO_COMPRESSED_OOPS).heapChangePercent(baseline) >= 15,
                () -> "uncompressed oops: " + describe(r.get(NO_COMPRESSED_OOPS), baseline)),
        () ->
            assertTrue(
                r.get(NO_COMPRESSED_CLASS_POINTERS).heapChangePercent(baseline) > 0,
                () ->
                    "16-byte headers: " + describe(r.get(NO_COMPRESSED_CLASS_POINTERS), baseline)),
        // 16-byte alignment buys compressed oops up to 64 GB with padding on every object
        () ->
            assertTrue(
                r.get(ALIGNMENT_16).heapChangePercent(baseline) > 0,
                () -> "16-byte alignment: " + describe(r.get(ALIGNMENT_16), baseline)));
  }

  @ParameterizedTest
  @EnumSource(LoadProfile.class)
  void heapMeasurementAgreesWithJolsExactObjectSizes(LoadProfile load) {
    // Two independent instruments: used-heap-after-GC (what a dashboard shows) and JOL's sum of
    // real object sizes, plus the order's slot in the holding array. They must agree within 5%,
    // or one of them is measuring something other than the order graph.
    for (FootprintResult r : byLoad.get(load).values()) {
      double expected = r.jolBytesPerOrder() + r.referenceBytes();
      double actual = r.heapBytesPerOrder();
      assertTrue(
          Math.abs(actual - expected) / expected <= 0.05,
          () ->
              "%s at %s: heap says %.1f B/order, JOL says %.1f"
                  .formatted(r.config(), load, actual, expected));
    }
  }

  @Test
  void perOrderCostIsIndependentOfLoadSoSavingsScaleLinearly() {
    for (JvmMemoryConfig config : JvmMemoryConfig.values()) {
      double normal = byLoad.get(LoadProfile.NORMAL).get(config).jolBytesPerOrder();
      double high = byLoad.get(LoadProfile.HIGH).get(config).jolBytesPerOrder();
      assertEquals(normal, high, 0.01, config + ": a byte saved per object is saved at any scale");
    }
    long savedAtNormal = bytesSavedByCompactHeaders(LoadProfile.NORMAL);
    long savedAtHigh = bytesSavedByCompactHeaders(LoadProfile.HIGH);
    // HIGH holds 20x NORMAL's orders, so it should save ~20x the bytes (loose: GC noise at small N)
    double ratio = savedAtHigh / (double) savedAtNormal;
    assertTrue(ratio > 15 && ratio < 25, "expected ~20x the absolute savings, got " + ratio);
  }

  private long bytesSavedByCompactHeaders(LoadProfile load) {
    var r = byLoad.get(load);
    return r.get(DEFAULT).liveHeapBytes() - r.get(COMPACT_HEADERS).liveHeapBytes();
  }

  private static String describe(FootprintResult r, FootprintResult baseline) {
    return "%.1f -> %.1f B/order (JOL %+.1f%%), heap %,d -> %,d B (%+.1f%%)"
        .formatted(
            baseline.jolBytesPerOrder(),
            r.jolBytesPerOrder(),
            r.jolChangePercent(baseline),
            baseline.liveHeapBytes(),
            r.liveHeapBytes(),
            r.heapChangePercent(baseline));
  }
}
