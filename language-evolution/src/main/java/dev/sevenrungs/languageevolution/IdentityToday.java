package dev.sevenrungs.languageevolution;

// This phase's guru rung in the artifact is a Project Valhalla value class (JEP 401), which needs
// a Valhalla early-access build from jdk.java.net/valhalla - not mainline JDK 25. Checked while
// planning this phase: curl through this sandbox's egress proxy to both jdk.java.net and
// download.java.net returns 403 (the same domains WebFetch already refused this session), so an
// EA build cannot be fetched here. The real value-class rung (Valhalla's own record syntax,
// IdentityException, flattened arrays) is kept verbatim as a non-compiled reference at
// language-evolution/docs/Valhalla.java.reference - see language-evolution/README.md for how to
// actually run it on a machine with real internet access.
//
// What follows instead is real and runnable on stock JDK 25: the three contrasts JEP 401 changes,
// each proven as they stand *today*, on an ordinary record, so the "before" half of the Valhalla
// story is measured rather than only asserted in prose.
import org.openjdk.jol.info.GraphLayout;

public final class IdentityToday {

  record Complex(double re, double im) {
    Complex times(Complex o) {
      return new Complex(re * o.re - im * o.im, re * o.im + im * o.re);
    }
  }

  static final class Signal {
    Complex[] samples = new Complex[1 << 12];
  }

  public static void main(String[] args) {
    Complex a = new Complex(1, 2), b = new Complex(1, 2);

    // Contrast 1: a record today still has identity. Two structurally-equal instances are
    // `equals()` but not `==`. A Valhalla value class would make `==` compare state instead.
    System.out.println("a == b: " + (a == b)); // false
    System.out.println(
        "a.equals(b) && same hashCode: " + (a.equals(b) && a.hashCode() == b.hashCode()));

    // Contrast 2: `synchronized` works fine on a record today - it still has a monitor, because
    // it still has identity. A Valhalla value class throws IdentityException here instead (there
    // is no such exception to catch on this JDK: this call simply succeeds).
    synchronized (a) {
      System.out.println("synchronized(a): completed normally (no IdentityException on this JDK)");
    }

    // Contrast 3: an array of records today is an array of references to separately-headed
    // objects, not a flattened buffer. GraphLayout's footprint shows real per-instance overhead
    // (a mark word + klass pointer per Complex) in addition to the reference array itself - what
    // JEP 401 lets the JVM collapse away for a small, non-volatile-field value class.
    // A second, unplanned real finding while writing this: JOL 0.17's default Unsafe-based field
    // offset lookup refuses record classes outright on this JDK ("can't get field offset on a
    // record class") - LayoutProbe's plain class in jvm-internals never hit this because it isn't
    // a record. The pom.xml wires -Djol.magicFieldOffset=true (JOL's own documented workaround)
    // into every run of this class for that reason.
    var s = new Signal();
    for (int i = 0; i < s.samples.length; i++) s.samples[i] = new Complex(i, -i);
    System.out.println(GraphLayout.parseInstance(s).toFootprint());

    System.out.println(s.samples[7].times(s.samples[8]));
  }
}
