package dev.sevenrungs.jvminternals.footprint.estimate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The oracle against sizes already established by JOL in {@code FootprintComparison}'s runs. */
class SizeOracleTest {
  static final String HIDDEN = "java.util.regex.Pattern$$Lambda/0x80000002f";
  static final List<String> CLASSES =
      List.of(
          "java.lang.Long",
          "dev.sevenrungs.jvminternals.footprint.OrderGraph$LineItem",
          "[B",
          "[J",
          "[Ljava.lang.Object;",
          HIDDEN);

  @Test
  void defaultLayoutHasTwelveByteHeaders() {
    var l = SizeOracle.measure(JvmMemoryConfig.DEFAULT, CLASSES, "");
    assertAll(
        () -> assertEquals(8, l.alignment()),
        () -> assertEquals(24L, l.objectSizes().get("java.lang.Long")),
        () ->
            assertEquals(
                32L,
                l.objectSizes().get("dev.sevenrungs.jvminternals.footprint.OrderGraph$LineItem")),
        () -> assertEquals(new SizeOracle.ArrayLayout(16, 1), l.arrays().get("[B")),
        () ->
            assertEquals(new SizeOracle.ArrayLayout(16, 4), l.arrays().get("[Ljava.lang.Object;")));
  }

  @Test
  void compactHeadersRepackFieldsAndShrinkArrayHeaders() {
    var l = SizeOracle.measure(JvmMemoryConfig.COMPACT_HEADERS, CLASSES, "");
    assertAll(
        () -> assertEquals(16L, l.objectSizes().get("java.lang.Long"), "24 -> 16, not 24 -> 24"),
        () ->
            assertEquals(
                24L,
                l.objectSizes().get("dev.sevenrungs.jvminternals.footprint.OrderGraph$LineItem")),
        () -> assertEquals(new SizeOracle.ArrayLayout(12, 1), l.arrays().get("[B")),
        () ->
            assertEquals(
                new SizeOracle.ArrayLayout(16, 8), l.arrays().get("[J"), "long[] stays 8-aligned"));
  }

  @Test
  void uncompressedOopsWidenReferenceElements() {
    var l = SizeOracle.measure(JvmMemoryConfig.NO_COMPRESSED_OOPS, CLASSES, "");
    assertEquals(8, l.arrays().get("[Ljava.lang.Object;").indexScale());
  }

  @Test
  void hiddenClassesCannotBeLoadedByNameAndAreReportedAsSuch() {
    var l = SizeOracle.measure(JvmMemoryConfig.DEFAULT, CLASSES, "");
    assertTrue(l.unresolved().contains(HIDDEN), l.unresolved().toString());
  }
}
