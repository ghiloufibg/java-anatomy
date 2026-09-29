package dev.sevenrungs.compilertooling.profiler.attribution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sevenrungs.compilertooling.profiler.attribution.AllocationAttribution.Unattributed;
import dev.sevenrungs.compilertooling.profiler.attribution.SavingsAttributionReport.Capture;
import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.LoadProfile;
import dev.sevenrungs.jvminternals.footprint.OrderGraph;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimator;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The whole chain on real child JVMs: {@code HistogramProbe} run under the real {@code
 * AllocationAgent} (asm, instrumenting {@link OrderGraph} only), the footprint estimated for every
 * configuration, and each estimate attributed to the agent's allocation sites (~7 JVMs, ~8 s).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SavingsAttributionReportTest {
  static final int ORDERS = LoadProfile.MEDIUM.scaled(0.1);
  static final String ORDER_METHOD = OrderGraph.class.getName() + ".order";

  private Capture capture;
  private Map<JvmMemoryConfig, FootprintEstimate> estimates;
  private Map<JvmMemoryConfig, AllocationAttribution> attributions;

  @BeforeAll
  @Timeout(300)
  void runUnderTheAgentThenEstimateAndAttribute() {
    capture = SavingsAttributionReport.captureDemo(ORDERS);
    estimates = FootprintEstimator.estimateAll(capture.liveSet(), JvmMemoryConfig.DEFAULT, "");
    attributions = SavingsAttributionReport.attributeAll(capture, JvmMemoryConfig.DEFAULT, "");
  }

  @Test
  void theAgentCountedExactlyTheOrdersOwnAllocations() {
    var byClass = capture.sites().byAllocatedClass();
    assertAll(
        () -> assertEquals(ORDERS, count(byClass, OrderGraph.Order.class.getName())),
        () -> assertEquals(3L * ORDERS, count(byClass, OrderGraph.LineItem.class.getName())),
        () -> assertEquals(ORDERS, count(byClass, "java.util.HashMap")));
  }

  @ParameterizedTest
  @EnumSource(value = JvmMemoryConfig.class, mode = EnumSource.Mode.EXCLUDE, names = "DEFAULT")
  void attributedPlusUnattributedIsExactlyTheEstimatedChange(JvmMemoryConfig target) {
    AllocationAttribution a = attributions.get(target);
    FootprintEstimate e = estimates.get(target);
    assertEquals(e.bytesAfter() - e.bytesBefore(), a.attributedDelta() + a.unattributedDelta());
  }

  @Test
  void lineItemSavingsGoToTheMethodThatAllocatesThem() {
    // 8 bytes saved per LineItem under compact headers (32 -> 24), all allocated by order()
    AllocationAttribution compact = attributions.get(JvmMemoryConfig.COMPACT_HEADERS);
    long lineItems =
        compact.sites().stream()
            .filter(s -> s.className().equals(OrderGraph.LineItem.class.getName()))
            .filter(s -> s.location().equals(ORDER_METHOD))
            .mapToLong(AllocationAttribution.SiteSaving::delta)
            .sum();
    assertEquals(-8L * 3 * ORDERS, lineItems);
  }

  @Test
  void jdkInternalAllocationsAreReportedAsUnattributedNotLost() {
    // HashMap$Node is made inside HashMap.put and Long inside Long.valueOf: the agent never sees
    // them, but the savings are real and must still show up
    AllocationAttribution compact = attributions.get(JvmMemoryConfig.COMPACT_HEADERS);
    for (String jdkMade : new String[] {"java.util.HashMap$Node", "java.lang.Long"}) {
      Unattributed u =
          compact.unattributed().stream()
              .filter(x -> x.className().equals(jdkMade))
              .findFirst()
              .orElseThrow(() -> new AssertionError(jdkMade + " missing from unattributed"));
      assertEquals(0, u.seenAllocations(), jdkMade);
      assertTrue(u.delta() < 0, jdkMade + " shrinks under compact headers");
    }
  }

  @Test
  void aboutHalfOfTheCompactHeaderSavingIsInTheApplicationsOwnAllocations() {
    // measured: 53% - LineItem, HashMap and Order are `new`ed in order(); HashMap$Node, Long, and
    // the arrays behind String/ArrayList/HashMap are made by the JDK on the application's behalf
    AllocationAttribution compact = attributions.get(JvmMemoryConfig.COMPACT_HEADERS);
    double share =
        compact.attributedDelta()
            / (double) (compact.attributedDelta() + compact.unattributedDelta());
    assertTrue(share > 0.4 && share < 0.65, "attributed share " + share);
  }

  private static long count(Map<String, java.util.List<AllocationSites.Site>> byClass, String c) {
    return byClass.getOrDefault(c, java.util.List.of()).stream()
        .mapToLong(AllocationSites.Site::count)
        .sum();
  }
}
