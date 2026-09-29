package dev.sevenrungs.compilertooling.profiler.attribution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

/**
 * Who, in application code, caused the live objects {@code AllocationAgent} can't see being
 * allocated.
 *
 * <p>The agent counts {@code new} instructions in the classes it rewrites; a {@code HashMap$Node}
 * made inside {@code HashMap.put} never passes through one. JFR's {@code jdk.OldObjectSample} does
 * see it: it samples allocations, keeps the sampled objects that are <em>still alive</em> when the
 * recording ends, and reports each with its size and allocation stack. Walking that stack up to the
 * first frame in an application class ({@code OrderGraph.order}, which called {@code put}) names
 * the code responsible; summing sampled sizes per class and caller estimates how the live bytes
 * split.
 *
 * <p>Why old-object samples rather than {@code jdk.ObjectAllocationSample}: the footprint is about
 * survivors, and allocation samples measure churn. Tried first, they left 85% of the live {@code
 * byte[]}s unattributed, because the probe's own garbage (rendering its histogram as text)
 * outweighed the orders' {@code byte[]}s in allocated bytes, sampled with no application frame on
 * the stack.
 *
 * <p>This is sampling, not counting: JFR samples when a thread refills its TLAB, so the number of
 * samples depends on TLAB size and on how much the run allocates, and the old-object queue caps how
 * many are kept. A rarely allocated class may get no sample and stay unattributed. Objects that
 * died after the last GC are still reported, so collect before the recording ends.
 */
public record JfrAllocationCallers(Map<String, Map<String, Long>> weightByClassAndCaller) {

  /** The caller recorded for a sample with no application frame anywhere on its stack. */
  public static final String NO_APPLICATION_FRAME = "(no application frame)";

  public JfrAllocationCallers {
    weightByClassAndCaller = Map.copyOf(weightByClassAndCaller);
  }

  /** No recording: every allocation the agent missed stays unattributed. */
  public static JfrAllocationCallers none() {
    return new JfrAllocationCallers(Map.of());
  }

  /**
   * JVM flags that record old-object samples with allocation stacks into {@code recording}, dumped
   * at exit. The small fixed TLAB is a sampling-density knob only - it doesn't change object
   * layout: with the default adaptive TLABs a short run gets a few dozen samples, with 4 KB ones
   * thousands. The queue size is how many sampled live objects JFR keeps (default 256).
   */
  public static List<String> recordingFlags(Path recording) {
    return List.of(
        "-XX:FlightRecorderOptions:old-object-queue-size=100000",
        "-XX:StartFlightRecording:filename="
            + recording
            + ",jdk.OldObjectSample#enabled=true"
            + ",jdk.OldObjectSample#stackTrace=true"
            + ",jdk.OldObjectSample#cutoff=0s", // no path-to-GC-roots search: only stacks needed
        "-XX:TLABSize=4k",
        "-XX:-ResizeTLAB");
  }

  /**
   * Reads every {@code jdk.OldObjectSample} in {@code recording}, crediting each sampled object's
   * size to the first frame of its allocation stack whose class name starts with {@code
   * applicationPrefix}.
   */
  public static JfrAllocationCallers read(Path recording, String applicationPrefix) {
    Map<String, Map<String, Long>> weights = new HashMap<>();
    try {
      for (RecordedEvent e : RecordingFile.readAllEvents(recording)) {
        if (!e.getEventType().getName().equals("jdk.OldObjectSample")) {
          continue;
        }
        RecordedObject object = e.getValue("object");
        // histogram naming: JFR gives descriptor-style names ([B, [Ljava.lang.Object;) here
        String allocated = AllocationSites.histogramName(object.getClass("type").getName());
        weights
            .computeIfAbsent(allocated, k -> new HashMap<>())
            .merge(callerOf(e, applicationPrefix), e.getLong("objectSize"), Long::sum);
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    return new JfrAllocationCallers(weights);
  }

  /**
   * What this recording sampled beyond {@code baseline}, class by class and caller by caller - the
   * JFR counterpart of {@code LiveHistogram.minus}. Old-object samples cover the whole heap, so
   * without it the JDK's own startup objects (thousands of {@code byte[]}s, sampled with no
   * application frame) dilute the share credited to the application's callers.
   */
  public JfrAllocationCallers minus(JfrAllocationCallers baseline) {
    Map<String, Map<String, Long>> grown = new HashMap<>();
    weightByClassAndCaller.forEach(
        (className, callers) ->
            callers.forEach(
                (caller, weight) -> {
                  long remaining = weight - baseline.callersOf(className).getOrDefault(caller, 0L);
                  if (remaining > 0) {
                    grown.computeIfAbsent(className, k -> new HashMap<>()).put(caller, remaining);
                  }
                }));
    return new JfrAllocationCallers(grown);
  }

  /** Sampled live bytes per caller for one histogram class; empty if it was never sampled. */
  public Map<String, Long> callersOf(String className) {
    return weightByClassAndCaller.getOrDefault(className, Map.of());
  }

  private static String callerOf(RecordedEvent e, String applicationPrefix) {
    if (e.getStackTrace() == null) {
      return NO_APPLICATION_FRAME;
    }
    for (RecordedFrame f : e.getStackTrace().getFrames()) {
      String type = f.getMethod().getType().getName();
      if (type.startsWith(applicationPrefix)) {
        return type + "." + f.getMethod().getName();
      }
    }
    return NO_APPLICATION_FRAME;
  }
}
