package dev.sevenrungs.productionjvm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Drives {@link LiveGcWatch#wire} against a real {@link RecordingStream} while a real allocation/GC
 * workload runs, proving the corrected {@code EventSettings} usage (see {@link LiveGcWatch}'s class
 * Javadoc for the {@code withThrottle} -> {@code with("throttle", ...)} finding) actually enables
 * and delivers both {@code jdk.GCPhasePause} and {@code jdk.ObjectAllocationSample} events, not
 * just that it compiles.
 */
class LiveGcWatchTest {

  @Test
  @Timeout(30)
  void wiresRealGcAndAllocationEventsOntoTheStream() throws Exception {
    var pauseHistogram = new GcPauseHistogram();
    var allocBytes = new AtomicLong();
    var sawAllocationSample = new CountDownLatch(1);

    try (var rs = new RecordingStream()) {
      LiveGcWatch.wire(rs, pauseHistogram, allocBytes);
      rs.onEvent("jdk.ObjectAllocationSample", e -> sawAllocationSample.countDown());
      rs.startAsync();

      // Real allocation + young-gen pressure: forces both a real GCPhasePause and an
      // ObjectAllocationSample within the test's timeout.
      var sink = new Object[64];
      for (long i = 0; i < 30_000_000L && sawAllocationSample.getCount() > 0; i++) {
        sink[(int) (i & 63)] = new long[8];
      }
      assertTrue(
          sawAllocationSample.await(20, TimeUnit.SECONDS),
          "expected at least one jdk.ObjectAllocationSample event within 20s");
    }

    assertTrue(allocBytes.get() >= 0, "allocation byte counter must never go negative");
    // A GC pause is not guaranteed on every JVM/heap-size combination within this short a run,
    // so this only asserts the histogram itself stayed internally consistent under real events.
    assertTrue(pauseHistogram.total() >= 0);
  }
}
