package dev.sevenrungs.productionjvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves {@link OrderService}'s custom {@code com.acme.OrderProcessed} event is really recorded -
 * not just that the annotated {@code Event} subclass compiles - by running a real {@link
 * Recording}, dumping it, and reading the events back with {@link RecordingFile}.
 */
class OrderServiceTest {

  @Test
  @Timeout(30)
  void orderProcessedEventsAreRecordedWithRealFieldValues() throws Exception {
    Path dump = Files.createTempFile("orders-", ".jfr");
    try (Recording recording = new Recording()) {
      recording.enable("com.acme.OrderProcessed");
      recording.start();

      OrderService.process("o-test-1", 5);
      OrderService.process("o-test-2", 9);

      recording.stop();
      recording.dump(dump);
    }

    List<RecordedEvent> events = RecordingFile.readAllEvents(dump);
    assertFalse(events.isEmpty(), "expected at least one com.acme.OrderProcessed event");
    assertEquals(2, events.size());

    RecordedEvent first = events.get(0);
    assertEquals("o-test-1", first.getString("orderId"));
    assertEquals(5, first.getInt("lines"));
    assertEquals(5 * 1_999L, first.getLong("totalCents"));
    assertTrue(first.getDuration().toNanos() > 0, "event must have a real, positive duration");
  }
}
