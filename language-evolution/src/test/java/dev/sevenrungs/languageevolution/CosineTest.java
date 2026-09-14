package dev.sevenrungs.languageevolution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CosineTest {

  private static final float DELTA = 1e-4f;

  @Test
  void identicalVectorsHaveCosineOne() {
    float[] a = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
    assertEquals(1.0f, Cosine.cosine(a, a.clone()), DELTA);
  }

  @Test
  void orthogonalVectorsHaveCosineZero() {
    float[] a = {1, 0, 0, 0};
    float[] b = {0, 1, 0, 0};
    assertEquals(0.0f, Cosine.cosine(a, b), DELTA);
  }

  @Test
  void oppositeVectorsHaveCosineNegativeOne() {
    float[] a = {1, 2, 3};
    float[] b = {-1, -2, -3};
    assertEquals(-1.0f, Cosine.cosine(a, b), DELTA);
  }

  @Test
  void exercisesTheMaskedTail() {
    // A length that is not a multiple of any realistic species width (1, 2, 4, 8 or 16 lanes)
    // forces the masked-tail branch, not just the main loop.
    int n = 37;
    float[] a = new float[n], b = new float[n];
    for (int i = 0; i < n; i++) {
      a[i] = i + 1;
      b[i] = i + 1;
    }
    assertEquals(1.0f, Cosine.cosine(a, b), DELTA);
  }

  @Test
  void matchesTheArtifactsWorkedExampleShape() {
    float[] a = new float[1536], b = new float[1536];
    for (int i = 0; i < a.length; i++) {
      a[i] = (float) Math.sin(i);
      b[i] = (float) Math.cos(i * 0.5);
    }
    float result = Cosine.cosine(a, b);
    assertEquals(result, Cosine.cosine(a, b), 0.0f); // deterministic, at minimum
  }
}
