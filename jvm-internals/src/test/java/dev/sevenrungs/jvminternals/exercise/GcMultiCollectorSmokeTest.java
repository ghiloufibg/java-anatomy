package dev.sevenrungs.jvminternals.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Forks {@link AllocationWorkload} as a real child JVM under each of the three collectors this JDK
 * ships (confirmed present via {@code -XX:+PrintFlagsFinal} while planning this phase), with real
 * {@code -Xlog:gc*} output captured to a file - the exercise's "run it under G1, generational ZGC
 * and Shenandoah" requirement, actually proven rather than just documented.
 *
 * <p>Only the G1 run is checked against {@link GcLogParser} (which targets G1's grammar
 * specifically - see its class Javadoc): capturing real ZGC and Shenandoah logs while building this
 * parser showed both use structurally different log grammars from G1 and from each other, so their
 * checks here are deliberately format-agnostic completion checks instead.
 */
class GcMultiCollectorSmokeTest {

  @Test
  @Timeout(60)
  void g1LogsRealParsablePauseEvents() throws Exception {
    List<String> lines = runWorkload("-XX:+UseG1GC", "-Xmx32m", 3_000_000);
    var parser = new GcLogParser();
    var pauses = parser.parsePauses(lines);

    assertFalse(pauses.isEmpty(), "expected at least one real G1 pause event");
    assertFalse(parser.heapOccupancyAfterGc(pauses).isEmpty());
    for (var p : pauses) {
      assertTrue(p.durationMillis() >= 0, "duration must not be negative: " + p);
      assertTrue(p.heapTotalKb() > 0, "heap total must be positive: " + p);
    }
  }

  @Test
  @Timeout(60)
  void zgcRunsCleanlyAndProducesGcLogOutput() throws Exception {
    // Real ZGC output looks like "GC(3) Minor Collection (Allocation Rate) 60M(94%)->52M(81%)
    // 0.008s" - percentage occupancy and a trailing `s` duration, not GcLogParser's target shape.
    List<String> lines = runWorkload("-XX:+UseZGC", "-Xmx64m", 3_000_000);
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("Collection")),
        "expected at least one ZGC collection line in the captured log");
  }

  @Test
  @Timeout(60)
  void shenandoahRunsCleanlyAndProducesGcLogOutput() throws Exception {
    // Shenandoah has no separate start/end line pairing at all: every phase is one completion
    // line, e.g. "GC(0) Pause Init Mark (unload classes) 0.087ms".
    List<String> lines = runWorkload("-XX:+UseShenandoahGC", "-Xmx64m", 3_000_000);
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("Pause Init Mark")),
        "expected at least one Shenandoah init-mark pause in the captured log");
  }

  /** Runs the workload as a real child JVM and returns its captured -Xlog:gc* output. */
  private static List<String> runWorkload(String gcFlag, String heapFlag, int ticks)
      throws IOException, InterruptedException {
    Path logFile = Files.createTempFile("gc-", ".log");
    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("java.class.path");
    Process process =
        new ProcessBuilder(
                javaBin,
                heapFlag,
                gcFlag,
                "-Xlog:gc*,gc+heap=debug,gc+phases=debug:file=" + logFile,
                "-cp",
                classpath,
                "dev.sevenrungs.jvminternals.exercise.AllocationWorkload",
                String.valueOf(ticks))
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      fail("workload child process did not finish within 30s");
    }
    assertEquals(0, process.exitValue(), "workload child process must exit cleanly");
    return Files.readAllLines(logFile);
  }
}
