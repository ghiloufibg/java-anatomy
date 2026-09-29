package dev.sevenrungs.jvminternals.footprint.estimate;

import java.nio.file.Path;
import java.util.List;

/**
 * JVM flags for a JFR recording of {@code jdk.OldObjectSample}: sampled allocations that are still
 * alive when the recording ends, each with its size, array length and allocation stack.
 *
 * <p>Samples are taken when a thread refills its TLAB, so which objects get sampled is roughly
 * proportional to their size, and how many depends on TLAB size: with the default adaptive TLABs a
 * short run gets a few dozen samples, with the fixed 4 KB ones here thousands. That is a sampling
 * density knob only - it doesn't change object layout. The queue size is how many sampled live
 * objects JFR keeps (default 256).
 *
 * <p>JFR still reports sampled objects that died after the last GC, so collect before the recording
 * ends ({@link HistogramProbe} does).
 */
public final class OldObjectRecording {
  private OldObjectRecording() {}

  /** Flags that record old-object samples into {@code recording}, dumped when the JVM exits. */
  public static List<String> flags(Path recording) {
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
}
