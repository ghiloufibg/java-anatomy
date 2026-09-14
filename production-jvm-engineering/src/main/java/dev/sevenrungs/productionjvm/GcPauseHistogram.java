package dev.sevenrungs.productionjvm;

// A log2-bucketed pause histogram, pulled out of LiveGcWatch so it can be exercised directly by a
// test without going through a full RecordingStream. Bucket i holds pauses whose duration in
// microseconds has its highest set bit at position i, so bucket i covers [2^i, 2^(i+1)) us.
public final class GcPauseHistogram {

  private final long[] buckets = new long[64];

  public void record(long micros) {
    buckets[63 - Long.numberOfLeadingZeros(Math.max(1, micros))]++;
  }

  public long total() {
    long total = 0;
    for (long c : buckets) total += c;
    return total;
  }

  /** The upper bound (exclusive), in microseconds, of the bucket holding the p99 pause. */
  public long p99UpperBoundMicros() {
    long total = total();
    long seen = 0;
    int bucket = 0;
    for (; bucket < buckets.length && total > 0; bucket++) {
      seen += buckets[bucket];
      if (seen >= total * 0.99) break;
    }
    return 1L << (bucket + 1);
  }
}
