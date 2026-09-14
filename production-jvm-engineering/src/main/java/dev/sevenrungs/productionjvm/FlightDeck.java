package dev.sevenrungs.productionjvm;

// The exercise's "flight deck": a JFR event-streaming sidecar exporting the four signals a
// runbook actually needs - p99 GC pause, allocation rate, monitor contention time, and virtual
// thread pinning - as a single snapshot rather than a live per-second console print (that is
// LiveGcWatch's job; this one is meant to sit next to a service and answer "how has it been
// doing since I started watching").
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.consumer.RecordingStream;

public final class FlightDeck implements AutoCloseable {

  private final RecordingStream rs = new RecordingStream();
  private final GcPauseHistogram pauseHistogram = new GcPauseHistogram();
  private final AtomicLong allocBytes = new AtomicLong();
  private final AtomicLong contentionMillis = new AtomicLong();
  private final AtomicLong pinnedEvents = new AtomicLong();
  private final long startNanos = System.nanoTime();

  public FlightDeck() {
    rs.enable("jdk.GCPhasePause").withThreshold(Duration.ZERO);
    rs.enable("jdk.ObjectAllocationSample").with("throttle", "150/s");
    rs.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1));
    rs.enable("jdk.VirtualThreadPinned").withStackTrace();

    rs.onEvent("jdk.GCPhasePause", e -> pauseHistogram.record(e.getDuration().toNanos() / 1_000));
    rs.onEvent("jdk.ObjectAllocationSample", e -> allocBytes.addAndGet(e.getLong("weight")));
    rs.onEvent("jdk.JavaMonitorEnter", e -> contentionMillis.addAndGet(e.getDuration().toMillis()));
    rs.onEvent("jdk.VirtualThreadPinned", e -> pinnedEvents.incrementAndGet());
  }

  public void startAsync() {
    rs.startAsync();
  }

  /**
   * Stops the recording and blocks until every event already written has been delivered to this
   * stream's handlers - call this before {@link #snapshot()} for a final, complete reading; {@link
   * RecordingStream#close()} alone does not guarantee in-flight events are drained first.
   */
  public void stopAndDrain() {
    rs.stop();
  }

  public Metrics snapshot() {
    double elapsedSeconds = Math.max(1e-9, (System.nanoTime() - startNanos) / 1e9);
    return new Metrics(
        pauseHistogram.total(),
        pauseHistogram.p99UpperBoundMicros(),
        allocBytes.get() / elapsedSeconds,
        contentionMillis.get(),
        pinnedEvents.get());
  }

  @Override
  public void close() {
    rs.close();
  }

  public record Metrics(
      long gcPauseCount,
      long p99GcPauseUpperBoundMicros,
      double allocRateBytesPerSecond,
      long monitorContentionMillis,
      long virtualThreadPinnedEvents) {}
}
