package dev.sevenrungs.concurrency;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Exercise (phase 1): a writer-preferring read/write lock, exclusive and shared modes in one {@link
 * AbstractQueuedSynchronizer} subclass.
 *
 * <p>Unlike the JDK's {@code ReentrantReadWriteLock} in its default (non-fair) mode, this lock
 * blocks <em>new</em> readers the moment a writer starts waiting, even while the lock is still held
 * (or free) for readers — a writer that arrives is never starved behind a continuous stream of new
 * readers. It trades reader throughput for writer latency bounds, deliberately, so the trade-off is
 * visible in one class instead of buried in a fairness flag.
 *
 * <p>{@code state} packs two things into one AQS int: bit 0 is the exclusive (write) lock bit; bits
 * 1..31 are the active reader count, incremented in steps of {@link Sync#SHARED_UNIT}.
 *
 * <p><strong>A first version of this class gated new readers with an independent {@code
 * AtomicInteger waitingWriters} counter, incremented the moment a writer's {@code lock()} was
 * called.</strong> That deadlocks: {@code tryAcquireShared} is the same method AQS calls both for a
 * brand-new caller <em>and</em> to recheck an already-queued reader once it reaches the front of
 * the queue. A reader that is already queued ahead of the writer would recheck that same "is a
 * writer waiting" flag, see it still set, and re-park — forever, since the only thing that could
 * ever clear the flag (the writer finally acquiring) was itself stuck behind that very reader in
 * the same FIFO queue. Reproduced under load, the fix is what {@code ReentrantReadWriteLock}'s own
 * fair mode does: consult AQS's own queue via {@link
 * AbstractQueuedSynchronizer#hasQueuedPredecessors()} instead of a side-channel counter. That
 * method excludes the calling thread's own node from the count, so once a queued reader is
 * genuinely at the front, it sees no predecessor and proceeds — while a brand-new reader arriving
 * behind an already-queued writer correctly sees one and blocks. Writer preference falls out of
 * plain FIFO ordering: a writer that starts waiting is, from that moment on, always ahead of any
 * reader that arrives later.
 */
public final class WriterPreferringReadWriteLock implements ReadWriteLock {

  private static final class Sync extends AbstractQueuedSynchronizer {
    static final int WRITE_LOCKED = 1;
    static final int SHARED_UNIT = 2;

    // --- exclusive (write) mode ---

    @Override
    protected boolean tryAcquire(int unused) {
      // hasQueuedPredecessors() first: a writer must not barge ahead of anyone already
      // waiting (reader or writer), or FIFO order - and with it, writer preference - breaks.
      if (hasQueuedPredecessors()) return false;
      if (getState() != 0) return false; // a reader or the writer already holds it
      if (compareAndSetState(0, WRITE_LOCKED)) {
        setExclusiveOwnerThread(Thread.currentThread());
        return true;
      }
      return false;
    }

    @Override
    protected boolean tryRelease(int unused) {
      setExclusiveOwnerThread(null);
      setState(0);
      return true; // always allows a successor (reader or writer) to try
    }

    @Override
    protected boolean isHeldExclusively() {
      return getState() == WRITE_LOCKED && getExclusiveOwnerThread() == Thread.currentThread();
    }

    // --- shared (read) mode ---

    // Must be side-effect free beyond the CAS itself: AQS calls this both for a brand-new
    // caller and to recheck an already-queued reader once it is the queue's front node.
    // hasQueuedPredecessors() is safe for both: it excludes the calling thread's own node,
    // so a reader already at the front sees no predecessor and proceeds, while a fresh
    // reader arriving behind a queued writer sees one and correctly backs off.
    @Override
    protected int tryAcquireShared(int unused) {
      for (; ; ) {
        if (hasQueuedPredecessors()) {
          return -1; // someone (writer or reader) got in line first: FIFO, so wait our turn
        }
        int s = getState();
        if ((s & WRITE_LOCKED) != 0) {
          return -1; // a writer currently holds it
        }
        int next = s + SHARED_UNIT;
        if (compareAndSetState(s, next)) {
          return 1; // propagate: other blocked readers may also proceed
        }
      }
    }

    @Override
    protected boolean tryReleaseShared(int unused) {
      for (; ; ) {
        int s = getState();
        int next = s - SHARED_UNIT;
        if (compareAndSetState(s, next)) {
          // Only signal a successor once the last reader leaves: a waiting writer can only
          // actually acquire once the reader count has reached zero.
          return next >>> 1 == 0;
        }
      }
    }

    Condition newWriteCondition() {
      return new ConditionObject();
    }
  }

  private final Sync sync = new Sync();
  private final Lock readLock = new ReadLock();
  private final Lock writeLock = new WriteLock();

  @Override
  public Lock readLock() {
    return readLock;
  }

  @Override
  public Lock writeLock() {
    return writeLock;
  }

  private final class ReadLock implements Lock {
    @Override
    public void lock() {
      sync.acquireShared(1);
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
      sync.acquireSharedInterruptibly(1);
    }

    @Override
    public boolean tryLock() {
      return sync.tryAcquireShared(1) >= 0;
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
      return sync.tryAcquireSharedNanos(1, unit.toNanos(time));
    }

    @Override
    public void unlock() {
      sync.releaseShared(1);
    }

    @Override
    public Condition newCondition() {
      throw new UnsupportedOperationException(
          "read locks have no condition, as in ReentrantReadWriteLock");
    }
  }

  private final class WriteLock implements Lock {
    @Override
    public void lock() {
      sync.acquire(1);
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
      sync.acquireInterruptibly(1);
    }

    @Override
    public boolean tryLock() {
      return sync.tryAcquire(1);
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
      return sync.tryAcquireNanos(1, unit.toNanos(time));
    }

    @Override
    public void unlock() {
      sync.release(1);
    }

    @Override
    public Condition newCondition() {
      return sync.newWriteCondition();
    }
  }
}
