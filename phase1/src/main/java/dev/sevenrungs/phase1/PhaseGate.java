package dev.sevenrungs.phase1;

import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * A reusable "phase gate" built directly on {@link AbstractQueuedSynchronizer}. State encodes the
 * phase number; {@code await(n)} blocks until the gate reaches phase n.
 *
 * <p>Lessons: shared-mode acquire, why {@code tryAcquireShared} returns a propagation hint, and why
 * the state must be the <em>only</em> thing that decides who proceeds.
 *
 * <p><strong>A first version of {@link #await(int)} passed the caller's target phase straight
 * through as the shared-acquire argument.</strong> That deadlocks under virtual threads: AQS's
 * shared release wakes only the queue's head successor and, on success, propagates to the next node
 * <em>only if that same check also succeeds for it</em> — an assumption that holds for homogeneous
 * waiters (a {@code Semaphore} or {@code CountDownLatch}, where every waiter checks the identical
 * condition) but breaks the moment waiters want <em>different</em> phases. If a waiter for phase 3
 * happens to land ahead of a waiter for phase 1 in the queue (virtual thread start order gives no
 * ordering guarantee at all), the phase-3 waiter is woken, fails, and re-parks <em>without AQS ever
 * checking the phase-1 waiter behind it</em> — even though phase 1 may already be satisfied.
 * Reproduced reliably once this ran under a test harness whose scheduling happened to favor that
 * ordering. The fix below waits only for "one more advance than I have already observed" each
 * round, instead of jumping straight to the ultimate target: every waiter's threshold is then
 * always {@code currentPhase + 1}, and because phase only increases, no waiter queued later can
 * ever have a *smaller* threshold than one queued earlier. That restores the ordering AQS's
 * propagation actually relies on, at the cost of needing one {@link #advance()} per phase crossed
 * rather than one per caller — exactly the cost {@code advance()} was already paying in the demo
 * below.
 */
public final class PhaseGate {
  private static final class Sync extends AbstractQueuedSynchronizer {
    Sync() {
      setState(0);
    }

    // arg = the phase the caller is waiting for.
    // >0 : acquired and later waiters may also succeed (propagate)
    // <0 : must park. Must be side-effect free: AQS calls it repeatedly, both before
    //      enqueueing and after every unpark.
    @Override
    protected int tryAcquireShared(int wanted) {
      return getState() >= wanted ? 1 : -1;
    }

    // Release advances the phase (CAS loop because releasers can race).
    // Returning true wakes the head of the queue; propagation then wakes the rest.
    @Override
    protected boolean tryReleaseShared(int ignored) {
      for (; ; ) {
        int s = getState();
        if (compareAndSetState(s, s + 1)) return true;
      }
    }

    int phase() {
      return getState();
    }
  }

  private final Sync sync = new Sync();

  public void await(int phase) throws InterruptedException {
    // Wait a round at a time for "one more than I've observed", not for `phase` directly - see
    // the class Javadoc for why jumping straight to `phase` can deadlock with mixed waiters.
    while (sync.phase() < phase) {
      sync.acquireSharedInterruptibly(sync.phase() + 1);
    }
  }

  public boolean await(int phase, long nanos) throws InterruptedException {
    long deadline = System.nanoTime() + nanos;
    while (sync.phase() < phase) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return false;
      sync.tryAcquireSharedNanos(sync.phase() + 1, remaining);
    }
    return true;
  }

  public void advance() {
    sync.releaseShared(0);
  }

  public int phase() {
    return sync.phase();
  }

  public static void main(String[] args) throws Exception {
    var gate = new PhaseGate();
    for (int i = 1; i <= 3; i++) {
      final int want = i;
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  gate.await(want);
                  System.out.println("phase " + want + " reached by " + Thread.currentThread());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
    }
    for (int i = 0; i < 3; i++) {
      Thread.sleep(200);
      gate.advance();
    }
    Thread.sleep(200);
    // Exercise for the reader: why would a release before any acquire NOT be lost, and what
    // happens if tryAcquireShared had a side effect? (Answer: it is called both before
    // enqueuing and after every unpark.)
  }
}
