package dev.sevenrungs.compilertooling.profiler;

// The shared sink every Instrumenter implementation reports through, so a run's counts are
// comparable no matter which of the three code bases produced the instrumented class.
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class AllocationRecorder {
  private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

  private AllocationRecorder() {}

  /** Called from instrumented bytecode, immediately before the allocation it counts. */
  public static void record(String site) {
    COUNTS.computeIfAbsent(site, s -> new LongAdder()).increment();
  }

  /** A stable, sorted-by-site snapshot of every count recorded so far. */
  public static Map<String, Long> snapshot() {
    Map<String, Long> sorted = new TreeMap<>();
    COUNTS.forEach((site, adder) -> sorted.put(site, adder.sum()));
    return sorted;
  }

  /**
   * Prints {@link #snapshot()} to stdout, one line per site - what a real agent reports at exit.
   */
  public static void report() {
    snapshot().forEach((site, count) -> System.out.println(site + " = " + count));
  }

  /** Clears every recorded count. Test-only: production agents never need to reset this. */
  public static void reset() {
    COUNTS.clear();
  }
}
