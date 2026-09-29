package dev.sevenrungs.compilertooling.profiler.attribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sevenrungs.compilertooling.profiler.attribution.AllocationAttribution.SiteSaving;
import dev.sevenrungs.compilertooling.profiler.attribution.AllocationAttribution.Unattributed;
import dev.sevenrungs.compilertooling.profiler.attribution.AllocationSites.Site;
import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate.ClassEstimate;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The attribution arithmetic on hand-built estimates - no agent, no child JVM. */
class AllocationAttributionTest {

  @Test
  void aClassFullySeenByTheAgentIsCreditedEntirelyToItsSite() {
    var a =
        attribute(
            List.of(object("app.Line", 3000, -24_000)),
            site("app.Orders", "load", "app.Line", 3000));
    assertEquals(List.of(new SiteSaving("app.Orders.load", "app.Line", 3000, -24_000)), a.sites());
    assertTrue(a.unattributed().isEmpty());
  }

  @Test
  void aClassAllocatedOnlyInsideTheJdkIsUnattributed() {
    // HashMap$Node: created in HashMap.put, never by a `new` in application bytecode
    var a = attribute(List.of(object("java.util.HashMap$Node", 2000, -16_000)));
    assertTrue(a.sites().isEmpty());
    assertEquals(
        List.of(new Unattributed("java.util.HashMap$Node", 2000, 0, -16_000)), a.unattributed());
  }

  @Test
  void aPartlySeenClassSplitsBetweenItsSitesAndTheUnseenRest() {
    // 1000 live Strings, 400 allocated by visible app code (300 + 100), 600 elsewhere
    var a =
        attribute(
            List.of(object("java.lang.String", 1000, -8000)),
            site("app.A", "f", "java.lang.String", 300),
            site("app.B", "g", "java.lang.String", 100));
    assertEquals(-2400, a.deltaAt("app.A.f"));
    assertEquals(-800, a.deltaAt("app.B.g"));
    assertEquals(-4800, a.unattributedDelta());
  }

  @Test
  void moreAllocationsThanSurvivorsCapTheSeenShareAtOne() {
    // allocations are counted over the whole run; only 100 of 10,000 survived - still all of the
    // live instances were allocated by visible code, so all of the change is attributed
    var a =
        attribute(List.of(object("app.Tmp", 100, -800)), site("app.T", "run", "app.Tmp", 10_000));
    assertEquals(-800, a.attributedDelta());
    assertEquals(0, a.unattributedDelta());
  }

  @Test
  void roundingNeverLosesOrInventsBytes() {
    // three equal sites sharing -1000 bytes: -333.33 each rounds to -333, the -1 remainder goes
    // to the biggest site instead of appearing as a spurious "unattributed" byte
    var a =
        attribute(
            List.of(object("app.X", 3, -1000)),
            site("app.P", "a", "app.X", 1),
            site("app.P", "b", "app.X", 1),
            site("app.P", "c", "app.X", 1));
    assertEquals(-1000, a.attributedDelta());
    assertTrue(a.unattributed().isEmpty());
  }

  @Test
  void classesThatDontChangeAreLeftOut() {
    var a =
        attribute(
            List.of(object("java.util.ArrayList", 1000, 0)),
            site("app.A", "f", "java.util.ArrayList", 1000));
    assertTrue(a.sites().isEmpty());
    assertTrue(a.unattributed().isEmpty());
  }

  private static ClassEstimate object(String className, long instances, long delta) {
    long before = instances * 32;
    return new ClassEstimate(className, Kind.OBJECT, instances, before, before + delta, 0);
  }

  private static Site site(String owner, String method, String allocated, long count) {
    return new Site(owner, method, allocated, count);
  }

  private static AllocationAttribution attribute(List<ClassEstimate> classes, Site... sites) {
    var estimate =
        new FootprintEstimate(JvmMemoryConfig.DEFAULT, JvmMemoryConfig.COMPACT_HEADERS, classes);
    return AllocationAttribution.attribute(estimate, new AllocationSites(List.of(sites)));
  }
}
