package dev.sevenrungs.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class PhaseGateTest {

  @Test
  @Timeout(10)
  void waitersUnblockOnlyOncePhaseIsReached() throws Exception {
    var gate = new PhaseGate();
    List<Integer> completedInOrder = new CopyOnWriteArrayList<>();
    CountDownLatch allStarted = new CountDownLatch(3);

    // Three virtual-thread waiters for phases 1, 2 and 3, each recording when it unblocks.
    Thread w1 = Thread.ofVirtual().start(awaiter(gate, 1, completedInOrder, allStarted));
    Thread w2 = Thread.ofVirtual().start(awaiter(gate, 2, completedInOrder, allStarted));
    Thread w3 = Thread.ofVirtual().start(awaiter(gate, 3, completedInOrder, allStarted));
    allStarted.await();

    // None should have unblocked yet: the gate starts at phase 0.
    Thread.sleep(50);
    assertTrue(completedInOrder.isEmpty(), "no waiter should unblock before advance()");

    gate.advance(); // phase 1
    w1.join();
    assertEquals(List.of(1), completedInOrder);

    gate.advance(); // phase 2
    w2.join();
    assertEquals(List.of(1, 2), completedInOrder);

    gate.advance(); // phase 3
    w3.join();
    assertEquals(List.of(1, 2, 3), completedInOrder);
  }

  @Test
  @Timeout(10)
  void awaitOnAnAlreadyReachedPhaseReturnsImmediately() throws Exception {
    var gate = new PhaseGate();
    gate.advance();
    gate.advance();
    assertEquals(2, gate.phase());

    gate.await(1); // must not block: phase 1 was already reached
    gate.await(2);
    assertFalse(gate.await(3, 50_000_000L)); // phase 3 not reached: times out, returns false
  }

  private static Runnable awaiter(
      PhaseGate gate, int phase, List<Integer> completedInOrder, CountDownLatch started) {
    return () -> {
      started.countDown();
      try {
        gate.await(phase);
        completedInOrder.add(phase);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
  }
}
