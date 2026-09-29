package dev.sevenrungs.jvminternals.footprint.estimate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

/**
 * Lengths of sampled live arrays, per array class ({@code [B}, {@code [Ljava.lang.Object;}), read
 * from an {@link OldObjectRecording}.
 *
 * <p>A class histogram gives an array class's total bytes but not its lengths, so without samples
 * {@link FootprintEstimator} has to assume padding is spread uniformly. Real applications break
 * that assumption: every {@code ArrayList(3)} has an {@code Object[3]}, every {@code HashMap(4)} a
 * table of 4. With the real lengths, each array's size under the target layout is computed instead
 * of guessed.
 */
public record ArrayLengthSamples(Map<String, List<Integer>> lengthsByArrayClass) {

  public ArrayLengthSamples {
    Map<String, List<Integer>> copy = new HashMap<>();
    lengthsByArrayClass.forEach((k, v) -> copy.put(k, List.copyOf(v)));
    lengthsByArrayClass = Map.copyOf(copy);
  }

  /** No samples: every array falls back to the uniform-padding estimate. */
  public static ArrayLengthSamples none() {
    return new ArrayLengthSamples(Map.of());
  }

  /** Every sampled live array's class and length in {@code recording}. */
  public static ArrayLengthSamples read(Path recording) {
    Map<String, List<Integer>> lengths = new HashMap<>();
    try {
      for (RecordedEvent e : RecordingFile.readAllEvents(recording)) {
        if (!e.getEventType().getName().equals("jdk.OldObjectSample")) {
          continue;
        }
        RecordedObject object = e.getValue("object");
        String type = object.getClass("type").getName(); // histogram naming: [B, [Lx.Y;
        if (type.startsWith("[")) {
          lengths.computeIfAbsent(type, k -> new ArrayList<>()).add(e.getInt("arrayElements"));
        }
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    return new ArrayLengthSamples(lengths);
  }

  public List<Integer> lengthsOf(String arrayClass) {
    return lengthsByArrayClass.getOrDefault(arrayClass, List.of());
  }

  /**
   * These samples minus {@code baseline}'s, length by length - the counterpart of {@link
   * LiveHistogram#minus}: it removes the JDK's own startup arrays (strings' {@code byte[]}s of
   * every length) so the lengths left describe the application's arrays.
   */
  public ArrayLengthSamples minus(ArrayLengthSamples baseline) {
    Map<String, List<Integer>> left = new HashMap<>();
    lengthsByArrayClass.forEach(
        (arrayClass, lengths) -> {
          List<Integer> remaining = new ArrayList<>(lengths);
          for (Integer idle : baseline.lengthsOf(arrayClass)) {
            remaining.remove(idle); // one occurrence per baseline sample
          }
          if (!remaining.isEmpty()) {
            left.put(arrayClass, remaining);
          }
        });
    return new ArrayLengthSamples(left);
  }
}
