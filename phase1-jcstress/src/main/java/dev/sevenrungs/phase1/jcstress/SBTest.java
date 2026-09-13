package dev.sevenrungs.phase1.jcstress;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

/**
 * Store-buffering (SB): two threads each write their own variable, then read the other's. There is
 * no relationship at all between {@code x} and {@code y} beyond both being read and written by both
 * threads, so no synchronization primitive that only relates one write to one read on the
 * <em>same</em> variable can rule out either thread observing the other's write as not-yet-having-
 * happened while its own write is still sitting in a store buffer.
 *
 * <p>Only full sequential consistency (Java {@code volatile}) forbids the (0, 0) outcome. Compare
 * against {@link MPTest}, where the weaker release/acquire is already enough.
 */
public class SBTest {

  /** Plain fields: no ordering guarantee at all beyond program order within each thread. */
  @JCStressTest
  @State
  @Outcome(
      id = "1, 1",
      expect = Expect.ACCEPTABLE,
      desc = "both writes completed before either read: no interleaving")
  @Outcome(
      id = "0, 1",
      expect = Expect.ACCEPTABLE,
      desc = "actor1's read raced ahead of actor2's write; ordinary interleaving")
  @Outcome(
      id = "1, 0",
      expect = Expect.ACCEPTABLE,
      desc = "actor2's read raced ahead of actor1's write; ordinary interleaving")
  @Outcome(
      id = "0, 0",
      expect = Expect.ACCEPTABLE_INTERESTING,
      desc =
          "store buffering: JLS §17.4.5 draws no happens-before edge between these plain "
              + "accesses, so each actor's own write may still be sitting in a per-CPU store "
              + "buffer when the other actor's read executes, from every actor's local point of "
              + "view")
  public static class Plain {
    int x, y;

    @Actor
    public void actor1(II_Result r) {
      x = 1;
      r.r1 = y;
    }

    @Actor
    public void actor2(II_Result r) {
      y = 1;
      r.r2 = x;
    }
  }

  /** Release/acquire on both variables: still not enough to forbid store buffering. */
  @JCStressTest
  @State
  @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "no interleaving")
  @Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "ordinary interleaving")
  @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "ordinary interleaving")
  @Outcome(
      id = "0, 0",
      expect = Expect.ACCEPTABLE_INTERESTING,
      desc =
          "release/acquire (JEP 193) only creates a happens-before edge between a release and "
              + "an acquire that actually observes it. Since actor1 never acquires y's release "
              + "and actor2 never acquires x's release, JLS §17.4.5 still permits both plain-"
              + "looking reads to miss the other's write: release/acquire alone does not imply "
              + "sequential consistency across independent variables")
  public static class ReleaseAcquire {
    static final VarHandle X, Y;

    static {
      try {
        var l = MethodHandles.lookup();
        X = l.findVarHandle(ReleaseAcquire.class, "x", int.class);
        Y = l.findVarHandle(ReleaseAcquire.class, "y", int.class);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    int x, y;

    @Actor
    public void actor1(II_Result r) {
      X.setRelease(this, 1);
      r.r1 = (int) Y.getAcquire(this);
    }

    @Actor
    public void actor2(II_Result r) {
      Y.setRelease(this, 1);
      r.r2 = (int) X.getAcquire(this);
    }
  }

  /** Volatile: sequentially consistent, so store buffering can no longer be observed. */
  @JCStressTest
  @State
  @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "no interleaving")
  @Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "ordinary interleaving")
  @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "ordinary interleaving")
  @Outcome(
      id = "0, 0",
      expect = Expect.FORBIDDEN,
      desc =
          "JLS §17.4.4: volatile reads and writes are sequentially consistent, meaning a single "
              + "total order exists over every volatile access from every thread. In any total "
              + "order over {x=1, y=1, read y, read x}, at least one write precedes the read "
              + "that depends on it, so both reads seeing 0 is impossible")
  public static class Volatile {
    volatile int x, y;

    @Actor
    public void actor1(II_Result r) {
      x = 1;
      r.r1 = y;
    }

    @Actor
    public void actor2(II_Result r) {
      y = 1;
      r.r2 = x;
    }
  }
}
