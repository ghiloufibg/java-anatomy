package dev.sevenrungs.nativeinterop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sevenrungs.nativeinterop.QsortStructs.Entry;
import org.junit.jupiter.api.Test;

class QsortStructsTest {

  @Test
  void sortsScoresAscending() throws Throwable {
    Entry[] sorted = QsortStructs.qsortByScore(new double[] {3.5, 0.25, 9.0, 1.0, 4.4});

    double[] scores = new double[sorted.length];
    for (int i = 0; i < sorted.length; i++) scores[i] = sorted[i].score();
    for (int i = 1; i < scores.length; i++) {
      assertTrue(
          scores[i - 1] <= scores[i],
          "not ascending at index " + i + ": " + java.util.Arrays.toString(scores));
    }
  }

  @Test
  void keepsEachIdPairedWithItsOwnScoreAfterTheNativeReorder() throws Throwable {
    // Proves the whole struct moved together, not just the sort key: id 1 (score 0.25) must
    // still be paired with 0.25 after qsort() physically reordered the native array.
    double[] scores = {3.5, 0.25, 9.0, 1.0, 4.4};
    Entry[] sorted = QsortStructs.qsortByScore(scores);

    for (Entry e : sorted) {
      assertEquals(scores[e.id()], e.score(), 0.0, "id " + e.id() + " lost its original score");
    }
  }

  @Test
  void handlesASingleEntry() throws Throwable {
    Entry[] sorted = QsortStructs.qsortByScore(new double[] {42.0});
    assertEquals(1, sorted.length);
    assertEquals(0, sorted[0].id());
    assertEquals(42.0, sorted[0].score());
  }
}
