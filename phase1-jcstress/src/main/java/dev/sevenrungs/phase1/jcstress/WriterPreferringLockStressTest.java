package dev.sevenrungs.phase1.jcstress;

import dev.sevenrungs.phase1.WriterPreferringReadWriteLock;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.J_Result;

/**
 * Exercise (phase 1): "prove it with jcstress" for {@link WriterPreferringReadWriteLock}. Two
 * writers each increment a plain, non-volatile {@code long} counter under the write lock; one
 * reader reads it under the read lock. A plain {@code long} field is not even guaranteed atomic on
 * its own (JLS §17.7 permits word tearing for non-volatile 64-bit fields) - so any outcome other
 * than 0, 1 or 2 here would mean the lock is failing to establish a real happens-before edge
 * between {@code unlock()} and the next {@code lock()}, not just a benign race.
 *
 * <p>Across many trials, jcstress explores every interleaving of the two writers and the reader
 * that the JVM and hardware can actually produce, which a single fixed-timing unit test cannot.
 */
@JCStressTest
@State
@Outcome(
    id = "0",
    expect = Expect.ACCEPTABLE,
    desc = "the reader's read lock was granted before either writer's write lock")
@Outcome(
    id = "1",
    expect = Expect.ACCEPTABLE,
    desc = "the reader's read lock was granted between the two writers")
@Outcome(
    id = "2",
    expect = Expect.ACCEPTABLE,
    desc = "the reader's read lock was granted after both writers")
public class WriterPreferringLockStressTest {
  private final WriterPreferringReadWriteLock lock = new WriterPreferringReadWriteLock();
  private long counter;

  @Actor
  public void writer1() {
    lock.writeLock().lock();
    try {
      counter++;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Actor
  public void writer2() {
    lock.writeLock().lock();
    try {
      counter++;
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Actor
  public void reader(J_Result r) {
    lock.readLock().lock();
    try {
      r.r1 = counter;
    } finally {
      lock.readLock().unlock();
    }
  }
}
