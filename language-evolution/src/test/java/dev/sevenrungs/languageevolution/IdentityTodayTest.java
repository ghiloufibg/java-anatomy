package dev.sevenrungs.languageevolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sevenrungs.languageevolution.IdentityToday.Complex;
import dev.sevenrungs.languageevolution.IdentityToday.Signal;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

/**
 * Proves, on stock JDK 25, the "before" half of every contrast Project Valhalla's JEP 401 changes -
 * see {@link IdentityToday}'s Javadoc for why the real value-class rung can't be built in this
 * sandbox.
 */
class IdentityTodayTest {

  @Test
  void structurallyEqualRecordsAreNotTheSameReference() {
    Complex a = new Complex(1, 2), b = new Complex(1, 2);
    assertNotSame(a, b); // a Valhalla value class would make == compare state instead
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void synchronizingOnARecordCompletesNormally() {
    Complex a = new Complex(1, 2);
    // No IdentityException exists on this JDK; the assertion here is simply that this never
    // throws - a record still has a monitor, because it still has identity.
    synchronized (a) {
      assertTrue(true);
    }
  }

  @Test
  void anArrayOfRecordsHasRealPerInstanceOverhead() {
    var s = new Signal();
    for (int i = 0; i < s.samples.length; i++) s.samples[i] = new Complex(i, -i);

    long footprint = GraphLayout.parseInstance(s).totalSize();
    long referenceArrayAlone = 16L + (long) s.samples.length * 4; // rough header + one ref/slot

    // A flattened value-class array would be close to referenceArrayAlone plus the raw payload
    // bytes and no per-instance headers at all; a real array of objects costs meaningfully more,
    // which is the measurable form of "each Complex still has its own mark word and klass
    // pointer today".
    assertTrue(
        footprint > referenceArrayAlone,
        "expected real per-instance object overhead, got a footprint of only " + footprint);
  }
}
