package dev.sevenrungs.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * StoreBuffering is a hardware- and JIT-dependent demonstration, not a deterministic algorithm:
 * whether the opaque variant ever reproduces the reordering depends on the CPU's store-buffer
 * behavior. So this test asserts only the one thing JLS §17.4 actually guarantees — that the
 * sequentially-consistent (volatile) variant never observes both reads as zero — and merely
 * smoke-runs the opaque variant to confirm it executes cleanly.
 */
class StoreBufferingTest {

  @Test
  void volatileVariantNeverObservesTheReordering() throws InterruptedException {
    long seenBothZero = StoreBuffering.runTrials(50_000, true);
    assertEquals(0, seenBothZero, "sequentially consistent access must forbid this outcome");
  }

  @Test
  void opaqueVariantRunsWithoutError() throws InterruptedException {
    // Not asserted: on some hardware/JIT combinations the reordering may not reproduce in a
    // short run. The point of this rung is to look at the printed count from `main`, not to
    // pass/fail a test on it.
    long seenBothZero = StoreBuffering.runTrials(10_000, false);
    assertTrue(seenBothZero >= 0, "trial count must be non-negative");
  }
}
