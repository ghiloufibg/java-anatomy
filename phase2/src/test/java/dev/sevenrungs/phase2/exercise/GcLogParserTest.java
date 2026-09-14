package dev.sevenrungs.phase2.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sevenrungs.phase2.exercise.GcLogParser.ConcurrentCycleInterval;
import dev.sevenrungs.phase2.exercise.GcLogParser.DurationStats;
import dev.sevenrungs.phase2.exercise.GcLogParser.HeapOccupancyPoint;
import dev.sevenrungs.phase2.exercise.GcLogParser.PauseEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fixture lines below are copied verbatim from real {@code -Xlog:gc*,gc+heap=debug,gc+phases=debug}
 * output captured from this JDK (25.0.1, G1) while developing this class - not typed from memory or
 * a remembered blog post format.
 */
class GcLogParserTest {
  private final GcLogParser parser = new GcLogParser();

  @Test
  void parsesAnOrdinaryEvacuationPause() {
    var events =
        parser.parsePauses(
            List.of(
                "[0.064s][info ][gc          ] GC(0) Pause Young (Normal) (G1 Evacuation Pause)"
                    + " 77M->6M(128M) 3.989ms"));
    assertEquals(1, events.size());
    PauseEvent e = events.get(0);
    assertEquals(0, e.gcId());
    assertEquals(0.064, e.timestampSeconds());
    assertEquals("Pause Young (Normal) (G1 Evacuation Pause)", e.description());
    assertEquals(77 * 1024L, e.beforeKb());
    assertEquals(6 * 1024L, e.afterKb());
    assertEquals(128 * 1024L, e.heapTotalKb());
    assertEquals(3.989, e.durationMillis());
  }

  @Test
  void parsesAHumongousAllocationPauseWithConcurrentStart() {
    var events =
        parser.parsePauses(
            List.of(
                "[0.079s][info ][gc          ] GC(1) Pause Young (Concurrent Start)"
                    + " (G1 Humongous Allocation) 72M->8M(128M) 2.130ms"));
    assertEquals(
        "Pause Young (Concurrent Start) (G1 Humongous Allocation)", events.get(0).description());
  }

  @Test
  void parsesAnEvacuationFailurePause() {
    // This is the modern spelling of "to-space exhausted": G1 ran out of to-space regions.
    var events =
        parser.parsePauses(
            List.of(
                "[0.086s][info ][gc          ] GC(30) Pause Young (Normal)"
                    + " (G1 Humongous Allocation) (Evacuation Failure: Allocation) 28M->18M(32M)"
                    + " 2.524ms"));
    assertTrue(events.get(0).description().contains("Evacuation Failure: Allocation"));
  }

  @Test
  void parsesAFullGcPause() {
    var events =
        parser.parsePauses(
            List.of(
                "[0.094s][info ][gc          ] GC(31) Pause Full (G1 Compaction Pause)"
                    + " 12M->6M(16M) 8.822ms"));
    assertEquals("Pause Full (G1 Compaction Pause)", events.get(0).description());
    assertEquals(16 * 1024L, events.get(0).heapTotalKb());
  }

  @Test
  void parsesRemarkAndCleanupPausesWithNoParentheticalCause() {
    var events =
        parser.parsePauses(
            List.of(
                "[0.088s][info ][gc          ] GC(29) Pause Remark 27M->23M(32M) 0.501ms",
                "[0.097s][info ][gc          ] GC(29) Pause Cleanup 20M->20M(32M) 0.021ms"));
    assertEquals(2, events.size());
    assertEquals("Pause Remark", events.get(0).description());
    assertEquals("Pause Cleanup", events.get(1).description());
  }

  @Test
  void ignoresNonPauseLinesEntirely() {
    var events =
        parser.parsePauses(
            List.of(
                "[0.068s][debug][gc,heap     ] GC(0) Heap Before GC invocations=0 (full 0):",
                "[0.068s][debug][gc,phases   ] GC(0)   Pre Evacuate Collection Set: 0.11ms",
                "[0.007s][info ][gc,init] Version: 25.0.1+8-LTS (release)"));
    assertTrue(events.isEmpty());
  }

  @Test
  void pairsAConcurrentCycleStartAndEndMarker() {
    var cycles =
        parser.parseConcurrentCycles(
            List.of(
                "[0.081s][info ][gc          ] GC(29) Concurrent Mark Cycle",
                "[0.079s][info ][gc          ] GC(2) Concurrent Undo Cycle", // unrelated,
                // interleaved
                "[0.080s][info ][gc          ] GC(2) Concurrent Undo Cycle 0.294ms",
                "[0.098s][info ][gc          ] GC(29) Concurrent Mark Cycle 16.599ms"));
    assertEquals(2, cycles.size());

    ConcurrentCycleInterval undo =
        cycles.stream().filter(c -> c.gcId() == 2).findFirst().orElseThrow();
    assertEquals("Concurrent Undo Cycle", undo.phrase());
    assertEquals(0.079, undo.startSeconds());
    assertEquals(0.080, undo.endSeconds());
    assertEquals(0.294, undo.durationMillis());

    ConcurrentCycleInterval mark =
        cycles.stream().filter(c -> c.gcId() == 29).findFirst().orElseThrow();
    assertEquals("Concurrent Mark Cycle", mark.phrase());
    assertEquals(0.081, mark.startSeconds());
    assertEquals(0.098, mark.endSeconds());
    assertEquals(16.599, mark.durationMillis());
  }

  @Test
  void anEndMarkerWithNoMatchingStartIsIgnoredRatherThanCrashing() {
    var cycles =
        parser.parseConcurrentCycles(
            List.of("[0.080s][info ][gc          ] GC(2) Concurrent Undo Cycle 0.294ms"));
    assertTrue(cycles.isEmpty());
  }

  @Test
  void pauseHistogramGroupsByDescriptionWithCorrectStats() {
    var pauses =
        parser.parsePauses(
            List.of(
                "[0.064s][info ][gc          ] GC(0) Pause Young (Normal) (G1 Evacuation Pause)"
                    + " 77M->6M(128M) 2.000ms",
                "[0.070s][info ][gc          ] GC(1) Pause Young (Normal) (G1 Evacuation Pause)"
                    + " 77M->6M(128M) 4.000ms",
                "[0.075s][info ][gc          ] GC(2) Pause Full (G1 Compaction Pause) 12M->6M(16M)"
                    + " 8.000ms"));
    Map<String, DurationStats> histogram = parser.pauseHistogramByDescription(pauses);

    DurationStats evac = histogram.get("Pause Young (Normal) (G1 Evacuation Pause)");
    assertEquals(2, evac.count());
    assertEquals(2.0, evac.minMillis());
    assertEquals(4.0, evac.maxMillis());
    assertEquals(3.0, evac.avgMillis());

    // Sorted by total time spent, descending: Full's 8ms (1 event) beats Evac's 6ms total (2
    // events).
    assertEquals(
        List.of("Pause Full (G1 Compaction Pause)", "Pause Young (Normal) (G1 Evacuation Pause)"),
        List.copyOf(histogram.keySet()));
  }

  @Test
  void durationBucketsCoverEveryRangeAndAreAlwaysPresent() {
    var pauses =
        parser.parsePauses(
            List.of(
                "[0.001s][info ][gc          ] GC(0) Pause Remark 1M->1M(1M) 0.500ms",
                "[0.002s][info ][gc          ] GC(1) Pause Remark 1M->1M(1M) 5.000ms",
                "[0.003s][info ][gc          ] GC(2) Pause Remark 1M->1M(1M) 50.000ms",
                "[0.004s][info ][gc          ] GC(3) Pause Remark 1M->1M(1M) 500.000ms",
                "[0.005s][info ][gc          ] GC(4) Pause Remark 1M->1M(1M) 5000.000ms"));
    Map<String, Long> buckets = parser.pauseCountByDurationBucket(pauses);
    assertEquals(1L, buckets.get("< 1ms"));
    assertEquals(1L, buckets.get("1-10ms"));
    assertEquals(1L, buckets.get("10-100ms"));
    assertEquals(1L, buckets.get("100ms-1s"));
    assertEquals(1L, buckets.get(">= 1s"));
  }

  @Test
  void heapOccupancyCurveTracksTheAfterValueOfEveryPauseInOrder() {
    var pauses =
        parser.parsePauses(
            List.of(
                "[0.010s][info ][gc          ] GC(0) Pause Remark 10M->4M(32M) 1.000ms",
                "[0.020s][info ][gc          ] GC(1) Pause Remark 10M->2M(32M) 1.000ms"));
    List<HeapOccupancyPoint> curve = parser.heapOccupancyAfterGc(pauses);
    assertEquals(2, curve.size());
    assertEquals(0.010, curve.get(0).timestampSeconds());
    assertEquals(4 * 1024L, curve.get(0).afterKb());
    assertEquals(0.020, curve.get(1).timestampSeconds());
    assertEquals(2 * 1024L, curve.get(1).afterKb());
  }
}
