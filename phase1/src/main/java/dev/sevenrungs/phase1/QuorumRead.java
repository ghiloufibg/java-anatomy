package dev.sevenrungs.phase1;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;

/**
 * Structured concurrency (JEP 505) + scoped values (JEP 506), JDK 25 API shape.
 *
 * <p>A custom {@link StructuredTaskScope.Joiner} that returns as soon as N replicas agree,
 * cancelling the rest.
 *
 * <p>Guru points: forks are virtual threads; scope lifetime is a tree, so the {@code ScopedValue}
 * bound in {@code main} is visible in every fork without passing it; cancellation is cooperative
 * and lands as interruption at the next blocking operation.
 *
 * <p>Run: {@code javac --release 25 --enable-preview QuorumRead.java && java --enable-preview
 * QuorumRead}. Check the JEP status for your JDK: the Joiner API stabilised in the 5th preview (JEP
 * 505).
 */
public final class QuorumRead {
  static final ScopedValue<String> TRACE_ID = ScopedValue.newInstance();

  /** Completes when {@code quorum} forks agree on the same value. */
  static final class Quorum<T> implements StructuredTaskScope.Joiner<T, T> {
    private final int quorum;
    private final Map<T, Integer> votes = new ConcurrentHashMap<>();
    private volatile T winner;

    Quorum(int quorum) {
      this.quorum = quorum;
    }

    // Called on the thread that finished the subtask, not the owner. Return true to stop.
    @Override
    public boolean onComplete(StructuredTaskScope.Subtask<? extends T> st) {
      if (st.state() != StructuredTaskScope.Subtask.State.SUCCESS) return false;
      int n = votes.merge(st.get(), 1, Integer::sum);
      if (n >= quorum) {
        winner = st.get(); // cancels remaining forks
        return true;
      }
      return false;
    }

    @Override
    public T result() {
      if (winner == null) throw new IllegalStateException("no quorum for " + TRACE_ID.get());
      return winner;
    }
  }

  static String readReplica(int i) throws InterruptedException {
    Thread.sleep(50L * (i + 1)); // interruption arrives here when cancelled
    return "v42" + (i == 2 ? "-stale" : ""); // replica 2 disagrees
  }

  public static void main(String[] args) throws Exception {
    System.out.println(quorumRead("req-7f3a")); // v42, replicas 3 and 4 were cancelled
  }

  /** Extracted from {@link #main} so tests can drive it with their own trace id. */
  static String quorumRead(String traceId) throws Exception {
    return quorumRead(traceId, 2);
  }

  /** Overload letting tests force a quorum that four replicas cannot reach. */
  static String quorumRead(String traceId, int quorum) throws Exception {
    return ScopedValue.where(TRACE_ID, traceId)
        .call(
            () -> {
              try (var scope = StructuredTaskScope.open(new Quorum<String>(quorum))) {
                for (int i = 0; i < 4; i++) {
                  int r = i;
                  scope.fork(() -> readReplica(r));
                }
                return scope.join(); // owner blocks; joiner decides when
              }
            });
  }
}
