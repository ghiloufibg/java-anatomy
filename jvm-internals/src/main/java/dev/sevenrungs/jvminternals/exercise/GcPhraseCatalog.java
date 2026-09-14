package dev.sevenrungs.jvminternals.exercise;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exercise (phase 2): "for every distinct log phrase, write down the collector phase it names and
 * the JEP or source file that defines it" - as living, matchable code, not just prose, the same way
 * phase 1 put its JLS citations directly in {@code @Outcome(desc = ...)}.
 *
 * <p>Entries are ordered most-specific-first and matched by substring, because a real G1 pause
 * description is composed (e.g. {@code "Young (Normal) (G1 Humongous Allocation) (Evacuation
 * Failure: Allocation)"} can carry three of these phrases at once) - {@link #citationsFor(String)}
 * returns every entry whose keyword appears in the description, in catalog order.
 */
public final class GcPhraseCatalog {

  public record Entry(String keyword, String phase, String citation) {}

  private static final List<Entry> ENTRIES =
      List.of(
          new Entry(
              "Evacuation Failure",
              "G1 evacuation failure (the modern spelling of \"to-space exhausted\")",
              "G1 ran out of to-space regions mid-evacuation and had to leave some objects "
                  + "in place instead of copying them out. src/hotspot/share/gc/g1/g1EvacFailure.cpp;"
                  + " raise -XX:G1ReservePercent or the heap size to avoid it."),
          new Entry(
              "Concurrent Start",
              "G1's initial-mark pause",
              "Piggybacked on an ordinary young collection to begin a concurrent marking "
                  + "cycle. JEP 248; src/hotspot/share/gc/g1/g1CollectedHeap.cpp (initiate "
                  + "concurrent mark)."),
          new Entry(
              "Prepare Mixed",
              "the last young-only pause before mixed collections begin",
              "Follows the concurrent cycle's cleanup pause; the next few young pauses become "
                  + "\"Mixed\" once this fires. JEP 248 (mixed-collection design)."),
          new Entry(
              "(Mixed)",
              "a mixed collection",
              "Evacuates both young regions and a subset of old regions chosen by their "
                  + "live-data ratio from the last concurrent marking cycle. JEP 248."),
          new Entry(
              "G1 Humongous Allocation",
              "a young collection triggered by a humongous object allocation",
              "A single object over half a region's size (region size logged at startup as "
                  + "\"Heap Region Size\") needed contiguous regions of its own; not covered by "
                  + "the JLS, it is a G1-specific allocation policy. "
                  + "src/hotspot/share/gc/g1/g1CollectedHeap.cpp (humongous object allocation path)."),
          new Entry(
              "G1 Evacuation Pause",
              "an ordinary, fully stop-the-world young-generation evacuation",
              "The default G1 pause: copies live objects out of the collection set. JEP 248 "
                  + "and the original G1 paper (Detlefs et al.)."),
          new Entry(
              "Pause Remark",
              "the second STW pause of a concurrent marking cycle",
              "Finishes marking by draining the SATB queues, then computes per-region "
                  + "liveness. src/hotspot/share/gc/g1/g1ConcurrentMark.cpp (remark)."),
          new Entry(
              "Pause Cleanup",
              "the third STW pause of a concurrent marking cycle",
              "Reclaims regions found to be completely empty, and picks the old regions that "
                  + "will make up the upcoming mixed-collection set. "
                  + "src/hotspot/share/gc/g1/g1ConcurrentMark.cpp (cleanup)."),
          new Entry(
              "Pause Full",
              "a full, mostly single-threaded stop-the-world compaction",
              "G1's fallback when it cannot keep up any other way - the one you page on. "
                  + "JEP 307 (parallel full GC for G1); "
                  + "src/hotspot/share/gc/g1/g1FullCollector.cpp."),
          new Entry(
              "Concurrent Undo Cycle",
              "an abandoned concurrent marking cycle",
              "The initial-mark pause fired, but the heap turned out not to need a real "
                  + "marking pass after all, so the cycle was cancelled before doing real work. "
                  + "src/hotspot/share/gc/g1/g1ConcurrentMark.cpp / g1Policy.cpp (cycle-abort path)."),
          new Entry(
              "Concurrent Mark Cycle",
              "the concurrent marking phase",
              "Traces the live object graph concurrently with the running application, "
                  + "between the initial-mark and remark pauses. JEP 248; "
                  + "src/hotspot/share/gc/g1/g1ConcurrentMark.cpp."));

  private GcPhraseCatalog() {}

  /** Every catalog entry whose keyword appears in {@code description}, in catalog order. */
  public static List<Entry> citationsFor(String description) {
    return ENTRIES.stream().filter(e -> description.contains(e.keyword())).toList();
  }

  /** The single best (first-matching, most specific) citation, if any keyword matches at all. */
  public static Optional<Entry> bestCitationFor(String description) {
    return ENTRIES.stream().filter(e -> description.contains(e.keyword())).findFirst();
  }

  /** All catalog entries, for printing the full reference table. */
  public static List<Entry> all() {
    return ENTRIES;
  }

  /** Ties a phrase back to its entry by keyword, for tests and tools that already have the key. */
  public static Map<String, Entry> byKeyword() {
    Map<String, Entry> map = new LinkedHashMap<>();
    for (Entry e : ENTRIES) map.put(e.keyword(), e);
    return map;
  }
}
