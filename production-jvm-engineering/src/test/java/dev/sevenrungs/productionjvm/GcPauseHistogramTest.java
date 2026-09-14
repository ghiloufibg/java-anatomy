package dev.sevenrungs.productionjvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GcPauseHistogramTest {

  @Test
  void bucketsByLog2AndTracksTotal() {
    var h = new GcPauseHistogram();
    h.record(1);
    h.record(100);
    h.record(1_000);
    assertEquals(3, h.total());
  }

  @Test
  void p99UpperBoundCoversASlowTailThatIsMoreThanOnePercentOfTheTotal() {
    var h = new GcPauseHistogram();
    for (int i = 0; i < 90; i++) h.record(10); // fast pauses: 90% of the total
    for (int i = 0; i < 10; i++) h.record(10_000); // a slow tail: 10% of the total
    // With a 10% slow tail, rank 99 of 100 falls inside the slow bucket, not the fast one -
    // unlike a single 1%-of-total outlier, which (correctly) would NOT move the p99 bound: with
    // exactly 100 samples, the 99th-smallest value is still one of the 99 fast ones in that case.
    long bound = h.p99UpperBoundMicros();
    assertTrue(bound >= 10_000, "p99 upper bound " + bound + " must cover the slow tail");
  }

  @Test
  void p99UpperBoundIsNotPulledUpByASingleOutlierAtExactlyOnePercent() {
    var h = new GcPauseHistogram();
    for (int i = 0; i < 99; i++) h.record(10); // 99% of the total
    h.record(10_000); // exactly one outlier: the 100th of 100 samples, i.e. rank 100, not 99
    long bound = h.p99UpperBoundMicros();
    assertTrue(
        bound < 10_000,
        "a single outlier at rank 100 of 100 must not define the p99 bound, got " + bound);
  }

  @Test
  void emptyHistogramHasZeroTotalAndDoesNotThrow() {
    var h = new GcPauseHistogram();
    assertEquals(0, h.total());
    h.p99UpperBoundMicros(); // must not throw on an empty histogram
  }
}
