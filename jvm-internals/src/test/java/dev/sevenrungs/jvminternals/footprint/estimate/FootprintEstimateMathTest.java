package dev.sevenrungs.jvminternals.footprint.estimate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate.ClassEstimate;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate.Kind;
import dev.sevenrungs.jvminternals.footprint.estimate.SizeOracle.ArrayLayout;
import dev.sevenrungs.jvminternals.footprint.estimate.SizeOracle.Layouts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The estimator's pure parts - parsing and arithmetic - with no child JVM forked. */
class FootprintEstimateMathTest {

  // real rows captured from GC.class_histogram on JDK 25.0.1, including a hidden lambda class
  static final String HISTOGRAM =
      """
       num     #instances         #bytes  class name (module)
      -------------------------------------------------------
         1:         20052        1137720  [B (java.base@25.0.1)
        53:           256           6144  java.lang.Long (java.base@25.0.1)
       323:             8            128  java.util.regex.Pattern$$Lambda/0x80000002f (java.base@25.0.1)
      Total        178226        9811928
      """;

  // what SizeOracle measured on JDK 25.0.1 for these classes (see SizeOracleTest)
  static final Layouts DEFAULT_LAYOUTS =
      new Layouts(
          JvmMemoryConfig.DEFAULT,
          8,
          Map.of("java.lang.Long", 24L),
          Map.of("[B", new ArrayLayout(16, 1), "[J", new ArrayLayout(16, 8)),
          Set.of("java.util.regex.Pattern$$Lambda/0x80000002f"));
  static final Layouts COMPACT_LAYOUTS =
      new Layouts(
          JvmMemoryConfig.COMPACT_HEADERS,
          8,
          Map.of("java.lang.Long", 16L),
          Map.of("[B", new ArrayLayout(12, 1), "[J", new ArrayLayout(16, 8)),
          Set.of("java.util.regex.Pattern$$Lambda/0x80000002f"));

  @Test
  void parsesRowsStripsModulesAndSkipsHeaderAndTotal() {
    var h = LiveHistogram.parse(HISTOGRAM);
    assertEquals(
        List.of(
            new LiveHistogram.Entry("[B", 20052, 1137720),
            new LiveHistogram.Entry("java.lang.Long", 256, 6144),
            new LiveHistogram.Entry("java.util.regex.Pattern$$Lambda/0x80000002f", 8, 128)),
        h.entries());
    assertEquals(1137720 + 6144 + 128, h.totalBytes());
  }

  @Test
  void rejectsTextWithNoHistogramRows() {
    assertThrows(IllegalArgumentException.class, () -> LiveHistogram.parse("not a histogram"));
  }

  @Test
  void minusKeepsOnlyWhatGrewBeyondTheBaseline() {
    var loaded =
        new LiveHistogram(
            List.of(
                new LiveHistogram.Entry("java.lang.Long", 1256, 30144),
                new LiveHistogram.Entry("[B", 100, 4000),
                new LiveHistogram.Entry("app.Order", 1000, 40000)));
    var idle =
        new LiveHistogram(
            List.of(
                new LiveHistogram.Entry("java.lang.Long", 256, 6144),
                new LiveHistogram.Entry("[B", 120, 4200)));
    assertEquals(
        List.of(
            new LiveHistogram.Entry("java.lang.Long", 1000, 24000),
            new LiveHistogram.Entry("app.Order", 1000, 40000)),
        loaded.minus(idle).entries());
  }

  @Test
  void objectsUseTheOraclesRealLayoutNotHeaderArithmetic() {
    // "24 - 4, rounded up to 8" would predict 24 bytes: no change. The oracle knows the long moves
    // to offset 8 and the padding disappears: 16 bytes, 8 saved per instance.
    ClassEstimate longs = estimate(new LiveHistogram.Entry("java.lang.Long", 1000, 24000));
    assertEquals(Kind.OBJECT, longs.kind());
    assertEquals(16000, longs.bytesAfter());
    assertEquals(0, longs.uncertaintyBytes(), "object sizes are exact");
  }

  @Test
  void arraysAreReLaidOutFromTheirAverageLengthWithAStatedUncertainty() {
    // 1000 byte[] averaging 32 bytes under the default layout: base 16, expected padding 3.5, so
    // ~12.5 elements; under compact headers: 12 + 12.5 + 3.5 = 28 bytes each
    ClassEstimate bytes = estimate(new LiveHistogram.Entry("[B", 1000, 32000));
    assertEquals(Kind.ARRAY, bytes.kind());
    assertEquals(28000, bytes.bytesAfter());
    // padding is 0..7 on each side: half of each range, 3.5 + 3.5 = 7 bytes per array
    assertEquals(7000, bytes.uncertaintyBytes());
  }

  @Test
  void longArraysDontShrinkBecauseTheirElementsStayEightByteAligned() {
    ClassEstimate longs = estimate(new LiveHistogram.Entry("[J", 10, 800));
    assertEquals(800, longs.bytesAfter());
    assertEquals(0, longs.uncertaintyBytes(), "8-byte elements leave no padding to guess at");
  }

  @Test
  void hiddenClassesAreCarriedOverAndReportedNeverDropped() {
    var lambda = new LiveHistogram.Entry("java.util.regex.Pattern$$Lambda/0x80000002f", 8, 128);
    ClassEstimate c = estimate(lambda);
    assertEquals(Kind.UNRESOLVED, c.kind());
    assertEquals(128, c.bytesAfter());

    var all =
        FootprintEstimator.estimate(
            LiveHistogram.parse(HISTOGRAM), DEFAULT_LAYOUTS, COMPACT_LAYOUTS);
    assertEquals(128, all.unresolvedBytes());
    assertEquals(all.bytesBefore(), LiveHistogram.parse(HISTOGRAM).totalBytes());
  }

  @Test
  void arrayComponentNamesMatchWhatJolExpects() {
    assertEquals("byte", SizeOracle.arrayComponent("[B"));
    assertEquals("long", SizeOracle.arrayComponent("[J"));
    assertEquals("java.lang.Object", SizeOracle.arrayComponent("[Ljava.lang.String;"));
    assertEquals("java.lang.Object", SizeOracle.arrayComponent("[[I"), "nested: a reference");
  }

  private static ClassEstimate estimate(LiveHistogram.Entry entry) {
    return FootprintEstimator.estimate(
            new LiveHistogram(List.of(entry)), DEFAULT_LAYOUTS, COMPACT_LAYOUTS)
        .classes()
        .getFirst();
  }
}
