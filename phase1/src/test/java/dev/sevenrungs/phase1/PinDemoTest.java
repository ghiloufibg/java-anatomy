package dev.sevenrungs.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves, rather than assumes, JEP 491's guarantee: run the exact synchronized-then-block pattern
 * from {@link PinDemo} on a virtual thread while subscribed to the JVM's own {@code
 * jdk.VirtualThreadPinned} event, and confirm it never fires on the JDK this reactor targets (JDK
 * 25). Before JDK 24, this same pattern would have emitted at least one such event.
 */
class PinDemoTest {

  @Test
  @Timeout(15)
  void synchronizedBlockingCallDoesNotPinItsCarrier() throws Exception {
    AtomicInteger pinnedEvents = new AtomicInteger();
    try (RecordingStream rs = new RecordingStream()) {
      // Zero threshold: catch a pin of any duration, not just ones over JFR's default 20ms cutoff.
      rs.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO);
      rs.onEvent("jdk.VirtualThreadPinned", event -> pinnedEvents.incrementAndGet());
      rs.startAsync();
      Thread.sleep(200); // let the recording actually start collecting before we act

      Thread vthread =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      PinDemo.blockWhileSynchronized();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  });
      vthread.join();

      rs.stop(); // flush any buffered events before reading the counter
    }

    assertEquals(
        0,
        pinnedEvents.get(),
        "JEP 491 (JDK 24+) removed pinning for synchronized-then-block; a nonzero count here"
            + " means this JDK still pins for that pattern");
  }
}
