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
 * Message passing (MP): a writer publishes {@code data}, then raises a {@code flag}; a reader
 * checks the flag, then reads the data. Unlike {@link SBTest}, only <em>one</em> variable ({@code
 * flag}) needs a happens-before edge, because the reader's dependency on {@code data} is entirely
 * mediated through observing that flag. That is exactly what release/acquire (JEP 193) is for: once
 * the reader's acquire of {@code flag} observes the writer's release, JLS §17.4.5 guarantees every
 * write the writer made <em>before</em> the release — including {@code data} — is visible to the
 * reader. So, unlike SB, release/acquire alone is already enough to forbid the interesting outcome
 * here; {@code data} itself never needs to be volatile.
 */
public class MPTest {

  /** Both fields plain: nothing stops the reader from observing them out of publication order. */
  @JCStressTest
  @State
  @Outcome(
      id = "0, 0",
      expect = Expect.ACCEPTABLE,
      desc = "reader ran before the writer touched anything")
  @Outcome(
      id = "0, 1",
      expect = Expect.ACCEPTABLE,
      desc =
          "reader's flag read raced ahead of the writer's flag write, but its later data read "
              + "landed after the writer's data write - consistent with the writer's own "
              + "program order (data is written before flag)")
  @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "reader ran after both writes")
  @Outcome(
      id = "1, 0",
      expect = Expect.ACCEPTABLE_INTERESTING,
      desc =
          "the writer's two plain writes carry no ordering guarantee for an outside observer "
              + "(JLS §17.4.5): the reader can see the LATER write (flag) without yet seeing the "
              + "EARLIER one (data), because nothing links them")
  public static class Plain {
    int data;
    int flag;

    @Actor
    public void writer() {
      data = 1;
      flag = 1;
    }

    @Actor
    public void reader(II_Result r) {
      r.r1 = flag;
      r.r2 = data;
    }
  }

  /** Only the guard is release/acquire; data stays a plain field and is still safe. */
  @JCStressTest
  @State
  @Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "reader ran before the writer")
  @Outcome(
      id = "0, 1",
      expect = Expect.ACCEPTABLE,
      desc = "consistent with the writer's program order")
  @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "reader ran after both writes")
  @Outcome(
      id = "1, 0",
      expect = Expect.FORBIDDEN,
      desc =
          "JLS §17.4.5: a release on `flag` observed by an acquire on `flag` creates a "
              + "happens-before edge, and everything the writer did before the release "
              + "(including `data = 1`) is therefore guaranteed visible once the reader's "
              + "acquire sees flag == 1")
  public static class ReleaseAcquire {
    static final VarHandle FLAG;

    static {
      try {
        FLAG = MethodHandles.lookup().findVarHandle(ReleaseAcquire.class, "flag", int.class);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    int data;
    int flag;

    @Actor
    public void writer() {
      data = 1;
      FLAG.setRelease(this, 1);
    }

    @Actor
    public void reader(II_Result r) {
      r.r1 = (int) FLAG.getAcquire(this);
      r.r2 = data;
    }
  }

  /** Volatile flag: strictly stronger than release/acquire, so still forbidden. */
  @JCStressTest
  @State
  @Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "reader ran before the writer")
  @Outcome(
      id = "0, 1",
      expect = Expect.ACCEPTABLE,
      desc = "consistent with the writer's program order")
  @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "reader ran after both writes")
  @Outcome(
      id = "1, 0",
      expect = Expect.FORBIDDEN,
      desc =
          "JLS §17.4.4/§17.4.5: a volatile write/read pair is at least as strong as "
              + "release/acquire, so the same happens-before argument applies")
  public static class Volatile {
    int data;
    volatile int flag;

    @Actor
    public void writer() {
      data = 1;
      flag = 1;
    }

    @Actor
    public void reader(II_Result r) {
      r.r1 = flag;
      r.r2 = data;
    }
  }
}
