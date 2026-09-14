package dev.sevenrungs.jvminternals.exercise;

import dev.sevenrungs.jvminternals.exercise.GcLogParser.ConcurrentCycleInterval;
import dev.sevenrungs.jvminternals.exercise.GcLogParser.DurationStats;
import dev.sevenrungs.jvminternals.exercise.GcLogParser.HeapOccupancyPoint;
import dev.sevenrungs.jvminternals.exercise.GcLogParser.PauseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Exercise (phase 2): "write a parser that turns each log into pause histograms, concurrent cycle
 * timelines and heap-occupancy-after-GC curves" - this is the tool. Point it at a G1 {@code
 * -Xlog:gc*,gc+heap=debug,gc+phases=debug} log file (see jvm-internals/README.md for how to capture
 * one under each collector).
 */
public final class GcAutopsyReport {

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("usage: GcAutopsyReport <gc-log-file>");
      System.exit(2);
    }
    List<String> lines = Files.readAllLines(Path.of(args[0]));
    var parser = new GcLogParser();
    List<PauseEvent> pauses = parser.parsePauses(lines);
    List<ConcurrentCycleInterval> cycles = parser.parseConcurrentCycles(lines);

    System.out.println("=== Pause histogram (by description) ===");
    Map<String, DurationStats> byDescription = parser.pauseHistogramByDescription(pauses);
    if (byDescription.isEmpty()) {
      System.out.println("(no pauses matched - is this a G1 log? see the class Javadoc)");
    }
    byDescription.forEach(
        (description, stats) -> {
          System.out.printf(
              "%6d x  min %7.3fms  avg %7.3fms  max %7.3fms  %s%n",
              stats.count(), stats.minMillis(), stats.avgMillis(), stats.maxMillis(), description);
          GcPhraseCatalog.citationsFor(description)
              .forEach(e -> System.out.println("         -> " + e.phase() + ": " + e.citation()));
        });

    System.out.println();
    System.out.println("=== Pause histogram (by duration bucket) ===");
    parser
        .pauseCountByDurationBucket(pauses)
        .forEach((bucket, count) -> System.out.printf("%10s : %d%n", bucket, count));

    System.out.println();
    System.out.println("=== Concurrent cycle timeline ===");
    if (cycles.isEmpty()) {
      System.out.println("(no concurrent cycles observed in this log)");
    }
    cycles.forEach(
        c ->
            System.out.printf(
                "GC(%-4d) %-24s %8.3fs -> %8.3fs  (%7.3fms)%n",
                c.gcId(), c.phrase(), c.startSeconds(), c.endSeconds(), c.durationMillis()));

    System.out.println();
    System.out.println("=== Heap occupancy after GC (KB) ===");
    List<HeapOccupancyPoint> occupancy = parser.heapOccupancyAfterGc(pauses);
    for (HeapOccupancyPoint p : occupancy) {
      int bars = (int) Math.min(80, p.afterKb() / 1024); // 1 char per MB, capped
      System.out.printf(
          "%8.3fs [%7d KB] %s%n", p.timestampSeconds(), p.afterKb(), "#".repeat(bars));
    }

    System.out.println();
    System.out.println("=== Phrase catalog referenced above ===");
    new TreeSet<>(byDescription.keySet())
        .forEach(
            description ->
                GcPhraseCatalog.citationsFor(description)
                    .forEach(
                        e ->
                            System.out.println(
                                "\""
                                    + e.keyword()
                                    + "\" -> "
                                    + e.phase()
                                    + " ("
                                    + e.citation()
                                    + ")")));
  }
}
