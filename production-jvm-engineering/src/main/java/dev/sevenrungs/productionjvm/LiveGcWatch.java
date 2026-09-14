package dev.sevenrungs.productionjvm;

// JFR event streaming (JEP 349): consume the running JVM's events in-process, with no file,
// and turn them into live SLO signals. This is what agents like Cryostat and Datadog do.
//
// The artifact this class is adapted from calls
// rs.enable("jdk.ObjectAllocationSample").withThrottle("150/s") -
// EventSettings.withThrottle(String)
// does not exist anywhere in the real jdk.jfr.EventSettings API (confirmed by reflecting over the
// real class on this JDK: the only methods there are with(String,String), withStackTrace(),
// withoutStackTrace(), withoutThreshold(), withPeriod(Duration) and withThreshold(Duration)). The
// real way to set jdk.ObjectAllocationSample's "throttle" setting is the generic key/value form,
// .with("throttle", "150/s") - the same key a .jfc settings file would use for that event.
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.consumer.RecordingStream;

public final class LiveGcWatch {

  public static void main(String[] args) throws Exception {
    var pauseHistogram = new GcPauseHistogram();
    var allocBytes = new AtomicLong();
    try (var rs = new RecordingStream()) {
      wire(rs, pauseHistogram, allocBytes);
      rs.onFlush(
          () ->
              System.out.printf(
                  "GC pauses: %d, p99 < %d us, alloc ~%.1f MB/s%n",
                  pauseHistogram.total(),
                  pauseHistogram.p99UpperBoundMicros(),
                  allocBytes.getAndSet(0) / 1e6));
      rs.startAsync();
      churn(); // the app under observation
    }
  }

  /** Enables and wires the three events this rung watches onto an already-open stream. */
  static void wire(RecordingStream rs, GcPauseHistogram pauseHistogram, AtomicLong allocBytes) {
    rs.enable("jdk.GCPhasePause").withThreshold(Duration.ZERO);
    rs.enable("jdk.ObjectAllocationSample").with("throttle", "150/s"); // sampled, not every alloc
    rs.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(10)).withStackTrace();

    rs.onEvent("jdk.GCPhasePause", e -> pauseHistogram.record(e.getDuration().toNanos() / 1_000));
    rs.onEvent(
        "jdk.ObjectAllocationSample",
        e -> allocBytes.addAndGet(e.getLong("weight"))); // weight ~ bytes represented
    rs.onEvent(
        "jdk.JavaMonitorEnter",
        e ->
            System.out.printf(
                "contended %s for %d ms at %s%n",
                e.getClass("monitorClass").getName(),
                e.getDuration().toMillis(),
                e.getStackTrace().getFrames().getFirst().getMethod().getName()));
  }

  static void churn() throws InterruptedException {
    var sink = new Object[1024];
    for (long i = 0; i < 200_000_000L; i++) {
      sink[(int) (i & 1023)] = new long[8];
      if ((i & 0xFFFFF) == 0) Thread.sleep(50);
    }
  }
}
// Remote variant: RemoteRecordingStream over JMX gives the same API against another JVM.
// Guru question: why must the callbacks never block, and what happens to the JFR disk repository
// if they do? (Hint: jdk.jfr.internal + the repository chunk rotation.)
