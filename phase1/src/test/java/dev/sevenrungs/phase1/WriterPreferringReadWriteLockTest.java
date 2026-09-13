package dev.sevenrungs.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Written in a jtreg-regression-test idiom (no sleeps for synchronization where a barrier or a
 * polled thread state will do; a failure means the invariant genuinely broke, not that the machine
 * was briefly slow) rather than as an actual jtreg test, since jtreg only exists inside a built
 * OpenJDK source tree (phase 7).
 */
class WriterPreferringReadWriteLockTest {

  @Test
  @Timeout(5)
  void multipleReadersRunConcurrently() throws Exception {
    var lock = new WriterPreferringReadWriteLock();
    int n = 4;
    // Only passes if all n threads are holding the read lock at the same instant: if the lock
    // were secretly exclusive, the threads still queued for it would never reach the barrier
    // and this would time out instead.
    var allHoldingReadLock = new CyclicBarrier(n);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        futures.add(
            pool.submit(
                () -> {
                  lock.readLock().lock();
                  try {
                    allHoldingReadLock.await(3, TimeUnit.SECONDS);
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  } finally {
                    lock.readLock().unlock();
                  }
                }));
      }
      for (Future<?> f : futures) {
        f.get(4, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @Timeout(5)
  void writersAreMutuallyExclusive() throws Exception {
    var lock = new WriterPreferringReadWriteLock();
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxObserved = new AtomicInteger();
    int n = 6;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        futures.add(
            pool.submit(
                () -> {
                  lock.writeLock().lock();
                  try {
                    int now = active.incrementAndGet();
                    maxObserved.updateAndGet(m -> Math.max(m, now));
                    Thread.sleep(10);
                    active.decrementAndGet();
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  } finally {
                    lock.writeLock().unlock();
                  }
                }));
      }
      for (Future<?> f : futures) {
        f.get(4, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, maxObserved.get(), "no two writers may hold the lock at once");
  }

  @Test
  @Timeout(5)
  void readersAndWriterAreMutuallyExclusive() throws Exception {
    var lock = new WriterPreferringReadWriteLock();
    AtomicBoolean writerActive = new AtomicBoolean(false);
    AtomicBoolean sawOverlap = new AtomicBoolean(false);
    int readers = 4;
    ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
    try {
      Future<?> writer =
          pool.submit(
              () -> {
                for (int i = 0; i < 20; i++) {
                  lock.writeLock().lock();
                  try {
                    writerActive.set(true);
                    Thread.sleep(2);
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  } finally {
                    writerActive.set(false);
                    lock.writeLock().unlock();
                  }
                }
              });
      List<Future<?>> readerFutures = new ArrayList<>();
      for (int i = 0; i < readers; i++) {
        readerFutures.add(
            pool.submit(
                () -> {
                  for (int j = 0; j < 40; j++) {
                    lock.readLock().lock();
                    try {
                      if (writerActive.get()) sawOverlap.set(true);
                    } finally {
                      lock.readLock().unlock();
                    }
                  }
                }));
      }
      writer.get(4, TimeUnit.SECONDS);
      for (Future<?> f : readerFutures) {
        f.get(4, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertFalse(sawOverlap.get(), "a reader must never observe the writer as active");
  }

  @Test
  @Timeout(5)
  void writerArrivingWhileReadersHoldTheLockGoesAheadOfLaterReaders() throws Exception {
    var lock = new WriterPreferringReadWriteLock();
    List<String> completedInOrder = new CopyOnWriteArrayList<>();

    // Reader A takes the lock and holds it until told to release.
    Thread readerA =
        new Thread(
            () -> {
              lock.readLock().lock();
              try {
                sleepUntilInterrupted();
              } finally {
                lock.readLock().unlock();
              }
            });
    readerA.start();
    awaitState(readerA, Thread.State.TIMED_WAITING, Thread.State.WAITING);
    // readerA is now blocked *inside* the critical section on our own signal, i.e. it holds
    // the read lock. (It moved from RUNNABLE to WAITING only after acquiring, since lock() is
    // uncontended here.)

    // A writer arrives while reader A holds the lock: it must block...
    Thread writer =
        new Thread(
            () -> {
              lock.writeLock().lock();
              try {
                completedInOrder.add("writer");
              } finally {
                lock.writeLock().unlock();
              }
            });
    writer.start();
    awaitState(writer, Thread.State.WAITING, Thread.State.TIMED_WAITING);

    // ...and once it does, a *new* reader B must also block, even though nothing currently
    // holds an exclusive lock - this is the writer-preference guarantee under test.
    Thread readerB =
        new Thread(
            () -> {
              lock.readLock().lock();
              try {
                completedInOrder.add("readerB");
              } finally {
                lock.readLock().unlock();
              }
            });
    readerB.start();
    awaitState(readerB, Thread.State.WAITING, Thread.State.TIMED_WAITING);

    // Release reader A: the writer (already waiting) must run before reader B, which arrived
    // after the writer declared intent.
    readerA.interrupt();
    writer.join(4_000);
    readerB.join(4_000);

    assertEquals(List.of("writer", "readerB"), completedInOrder);
  }

  /** readerA holds the read lock until interrupted, simulating "still doing work". */
  private static void sleepUntilInterrupted() {
    try {
      Thread.sleep(Long.MAX_VALUE);
    } catch (InterruptedException expected) {
      // this is the signal to release the lock, not an error
    }
  }

  private static void awaitState(Thread t, Thread.State... anyOf) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      Thread.State s = t.getState();
      for (Thread.State candidate : anyOf) {
        if (s == candidate) return;
      }
      Thread.sleep(5);
    }
    throw new AssertionError(t.getName() + " never reached one of " + List.of(anyOf));
  }
}
