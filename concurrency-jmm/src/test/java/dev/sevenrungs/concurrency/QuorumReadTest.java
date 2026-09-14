package dev.sevenrungs.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.StructuredTaskScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class QuorumReadTest {

  @Test
  @Timeout(5)
  void quorumShortCircuitsBeforeTheSlowestReplica() throws Exception {
    // readReplica(i) sleeps 50*(i+1)ms: 50, 100, 150, 200. Replicas 0 and 1 both answer "v42",
    // reaching a quorum of 2 around the 100ms mark and cancelling replicas 2 and 3 before their
    // longer sleeps (150ms, 200ms) complete.
    long start = System.nanoTime();
    String result = QuorumRead.quorumRead("test-trace");
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertEquals("v42", result);
    assertTrue(
        elapsedMillis < 180,
        "quorum should short-circuit well before the 200ms slowest replica: took "
            + elapsedMillis
            + "ms");
  }

  @Test
  @Timeout(5)
  void unreachableQuorumFailsWithTheTraceIdInTheMessage() {
    // No value can collect 5 votes out of 4 replicas, so the Joiner's result() must throw - and
    // it reports the ScopedValue bound in the *owner* thread, proving the scope tree makes the
    // value visible without it being passed as a parameter anywhere. scope.join() itself wraps
    // whatever the Joiner throws in a StructuredTaskScope.FailedException (JEP 505's JDK 25
    // shape), so the IllegalStateException from Quorum.result() is the cause, not the exception
    // assertThrows sees directly.
    StructuredTaskScope.FailedException ex =
        assertThrows(
            StructuredTaskScope.FailedException.class,
            () -> QuorumRead.quorumRead("unreachable-trace", 5));
    IllegalStateException cause = assertInstanceOf(IllegalStateException.class, ex.getCause());
    assertTrue(cause.getMessage().contains("unreachable-trace"), cause.getMessage());
  }
}
