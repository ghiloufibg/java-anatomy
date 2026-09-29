package dev.sevenrungs.compilertooling.profiler.attribution;

import dev.sevenrungs.compilertooling.profiler.attribution.AllocationSites.Site;
import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate.ClassEstimate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Splits a {@link FootprintEstimate}'s per-class change between the application code that allocated
 * those objects and the objects no application allocation site accounts for.
 *
 * <p>For each class whose footprint changes, with {@code live} instances in the histogram and
 * {@code seen} allocations of it counted by {@code AllocationAgent}:
 *
 * <ul>
 *   <li>{@code min(1, seen / live)} of the change goes to the application's sites, split between
 *       them by their allocation counts;
 *   <li>the rest is <b>unattributed</b>: allocated where the agent doesn't look. {@code
 *       AllocationAgent} rewrites only classes matching its prefix, and skips {@code <init>} and
 *       {@code <clinit>}, so a {@code HashMap$Node} made inside {@code HashMap.put} or a {@code
 *       Long} boxed inside {@code Long.valueOf} shows up here even though your code caused it.
 * </ul>
 *
 * <p>The one assumption: allocations of a class survive in the same proportion whichever site made
 * them. The agent counts allocations, the histogram counts survivors; a site whose objects all die
 * young gets credited with savings that belong to a longer-lived site of the same class.
 */
public record AllocationAttribution(
    JvmMemoryConfig target,
    List<SiteSaving> sites,
    List<CallerSaving> callers,
    List<Unattributed> unattributed) {

  /** The part of {@code className}'s footprint change credited to one allocation site. */
  public record SiteSaving(String location, String className, long allocations, long delta) {}

  /**
   * The part of {@code className}'s change credited, through sampled JFR stack traces, to the
   * application method that caused an allocation the agent couldn't see.
   *
   * @param sampledBytes the JFR sample weight behind this share
   */
  public record CallerSaving(String location, String className, long sampledBytes, long delta) {}

  /** The part of {@code className}'s change no application code accounts for. */
  public record Unattributed(
      String className, long liveInstances, long seenAllocations, long delta) {}

  public AllocationAttribution {
    sites = List.copyOf(sites);
    callers = List.copyOf(callers);
    unattributed = List.copyOf(unattributed);
  }

  public static AllocationAttribution attribute(FootprintEstimate estimate, AllocationSites agent) {
    Map<String, List<Site>> byClass = agent.byAllocatedClass();
    List<SiteSaving> sites = new ArrayList<>();
    List<Unattributed> unattributed = new ArrayList<>();
    for (ClassEstimate c : estimate.classes()) {
      if (c.delta() == 0) {
        continue;
      }
      List<Site> allocating = byClass.getOrDefault(c.className(), List.of());
      long seen = allocating.stream().mapToLong(Site::count).sum();
      double seenFraction = c.instances() == 0 ? 0 : Math.min(1.0, seen / (double) c.instances());

      List<SiteSaving> shares = new ArrayList<>();
      long credited = 0;
      for (Site s : allocating) {
        long share = Math.round(c.delta() * seenFraction * s.count() / seen);
        shares.add(new SiteSaving(s.location(), c.className(), s.count(), share));
        credited += share;
      }
      long remainder = c.delta() - credited;
      if (seenFraction >= 1.0 && !shares.isEmpty()) {
        // every live instance is accounted for: the remainder is rounding, owed to the biggest site
        shares.sort(Comparator.comparingLong(SiteSaving::allocations).reversed());
        SiteSaving top = shares.getFirst();
        shares.set(
            0,
            new SiteSaving(
                top.location(), top.className(), top.allocations(), top.delta() + remainder));
      } else if (remainder != 0) {
        unattributed.add(new Unattributed(c.className(), c.instances(), seen, remainder));
      }
      sites.addAll(shares);
    }
    Comparator<Long> biggestChange = Comparator.comparingLong(Math::abs);
    sites.sort(Comparator.comparing(SiteSaving::delta, biggestChange).reversed());
    unattributed.sort(Comparator.comparing(Unattributed::delta, biggestChange).reversed());
    return new AllocationAttribution(estimate.target(), sites, List.of(), unattributed);
  }

  /**
   * Credits the unattributed part of each class to the application methods whose stacks JFR sampled
   * allocating it, split by sampled weight. Weight sampled with no application frame on the stack
   * (JDK startup, for instance) keeps its share unattributed.
   */
  public AllocationAttribution withCallers(JfrAllocationCallers jfr) {
    List<CallerSaving> credited = new ArrayList<>(callers);
    List<Unattributed> remaining = new ArrayList<>();
    for (Unattributed u : unattributed) {
      Map<String, Long> weights = jfr.callersOf(u.className());
      long total = weights.values().stream().mapToLong(Long::longValue).sum();
      if (total == 0) {
        remaining.add(u);
        continue;
      }
      List<CallerSaving> shares = new ArrayList<>();
      for (var w : weights.entrySet()) {
        long share = Math.round(u.delta() * (double) w.getValue() / total);
        if (!w.getKey().equals(JfrAllocationCallers.NO_APPLICATION_FRAME)) {
          shares.add(new CallerSaving(w.getKey(), u.className(), w.getValue(), share));
        }
      }
      long kept = u.delta() - shares.stream().mapToLong(CallerSaving::delta).sum();
      if (weights.containsKey(JfrAllocationCallers.NO_APPLICATION_FRAME) || shares.isEmpty()) {
        if (kept != 0) {
          remaining.add(
              new Unattributed(u.className(), u.liveInstances(), u.seenAllocations(), kept));
        }
      } else if (kept != 0) {
        // every sample had an application caller: the rounding remainder goes to the biggest one
        shares.sort(Comparator.comparingLong(CallerSaving::sampledBytes).reversed());
        CallerSaving top = shares.getFirst();
        shares.set(
            0,
            new CallerSaving(
                top.location(), top.className(), top.sampledBytes(), top.delta() + kept));
      }
      credited.addAll(shares);
    }
    Comparator<Long> biggestChange = Comparator.comparingLong(Math::abs);
    credited.sort(Comparator.comparing(CallerSaving::delta, biggestChange).reversed());
    remaining.sort(Comparator.comparing(Unattributed::delta, biggestChange).reversed());
    return new AllocationAttribution(target, sites, credited, remaining);
  }

  public long attributedDelta() {
    return sites.stream().mapToLong(SiteSaving::delta).sum();
  }

  /** The change credited through sampled JFR stacks: an estimate, unlike {@link #sites}. */
  public long callerDelta() {
    return callers.stream().mapToLong(CallerSaving::delta).sum();
  }

  public long unattributedDelta() {
    return unattributed.stream().mapToLong(Unattributed::delta).sum();
  }

  /**
   * The change credited to one code location, summed over every class it allocates - exactly (agent
   * sites) plus, if {@link #withCallers} was applied, what JFR sampled it causing.
   */
  public long deltaAt(String location) {
    return sites.stream()
            .filter(s -> s.location().equals(location))
            .mapToLong(SiteSaving::delta)
            .sum()
        + callers.stream()
            .filter(c -> c.location().equals(location))
            .mapToLong(CallerSaving::delta)
            .sum();
  }
}
