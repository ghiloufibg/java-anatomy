package dev.sevenrungs.compilertooling.profiler;

/**
 * The one instrumentation target all three {@link Instrumenter} implementations and the benchmark
 * run against, so their outputs are directly comparable: one object allocation site, one
 * reference-array site, one primitive-array site, one multi-dimensional-array site.
 */
public final class Workload {

  public static Object allocateObject() {
    return new Object();
  }

  public static int[] allocatePrimitiveArray(int n) {
    return new int[n];
  }

  public static String[] allocateReferenceArray(int n) {
    return new String[n];
  }

  public static int[][] allocateMultiArray() {
    return new int[2][3];
  }
}
