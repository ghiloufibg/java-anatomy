package dev.sevenrungs.concurrency.jcstress;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.IIII_Result;

/**
 * Independent reads of independent writes (IRIW): two writers each set their own, otherwise
 * unrelated, variable; two readers each read <em>both</em> variables, in opposite orders. If
 * reader1 sees {@code x} before {@code y} while reader2 sees {@code y} before {@code x}, the two
 * readers disagree about which write "happened first" globally — a violation of what the literature
 * calls multi-copy-atomicity (every thread agreeing on a single global order of writes to different
 * locations), even though neither writer nor either reader is individually doing anything unusual.
 *
 * <p>This is the one litmus test in this suite where release/acquire is <strong>not</strong> enough
 * (contrast {@link MPTest}): release/acquire only orders a specific release against the acquire
 * that observes it, and says nothing about what a third or fourth thread must agree on. Forbidding
 * IRIW needs full sequential consistency (JLS §17.4.4) — Java {@code volatile} — which is also why
 * this is the test the exercise expects to actually disagree between an x86 machine
 * (multi-copy-atomic in practice, so IRIW is vanishingly rare there even for plain fields) and an
 * ARM machine (not multi-copy-atomic, so it reproduces far more easily for plain/opaque fields).
 */
public class IRIWTest {

  @JCStressTest
  @State
  @Outcome(
      id = "1, 0, 1, 0",
      expect = Expect.ACCEPTABLE_INTERESTING,
      desc =
          "reader1 sees x before y, reader2 sees y before x: the two readers disagree on a "
              + "global order for x's and y's writes. JLS §17.4.5 permits this for plain "
              + "fields, since it never promises any total order across independent variables")
  public static class Plain {
    int x, y;

    @Actor
    public void writer1() {
      x = 1;
    }

    @Actor
    public void writer2() {
      y = 1;
    }

    @Actor
    public void reader1(IIII_Result r) {
      r.r1 = x;
      r.r2 = y;
    }

    @Actor
    public void reader2(IIII_Result r) {
      r.r3 = y;
      r.r4 = x;
    }
  }

  @JCStressTest
  @State
  @Outcome(
      id = "1, 0, 1, 0",
      expect = Expect.ACCEPTABLE_INTERESTING,
      desc =
          "release/acquire only creates a happens-before edge between a specific release and "
              + "the acquire that observes it (JLS §17.4.5); it makes no promise across the "
              + "*other* writer/reader pair, so the two readers can still disagree on the "
              + "global order of x and y")
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
    public void writer1() {
      X.setRelease(this, 1);
    }

    @Actor
    public void writer2() {
      Y.setRelease(this, 1);
    }

    @Actor
    public void reader1(IIII_Result r) {
      r.r1 = (int) X.getAcquire(this);
      r.r2 = (int) Y.getAcquire(this);
    }

    @Actor
    public void reader2(IIII_Result r) {
      r.r3 = (int) Y.getAcquire(this);
      r.r4 = (int) X.getAcquire(this);
    }
  }

  @JCStressTest
  @State
  @Outcome(
      id = "1, 0, 1, 0",
      expect = Expect.FORBIDDEN,
      desc =
          "JLS §17.4.4: volatile accesses are sequentially consistent, meaning a single total "
              + "order exists over ALL volatile reads and writes from every thread. In any such "
              + "total order, either x=1 precedes y=1 or vice versa, and every reader must agree "
              + "with that one order - the two readers disagreeing is therefore impossible")
  public static class Volatile {
    volatile int x, y;

    @Actor
    public void writer1() {
      x = 1;
    }

    @Actor
    public void writer2() {
      y = 1;
    }

    @Actor
    public void reader1(IIII_Result r) {
      r.r1 = x;
      r.r2 = y;
    }

    @Actor
    public void reader2(IIII_Result r) {
      r.r3 = y;
      r.r4 = x;
    }
  }
}
