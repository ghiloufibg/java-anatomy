package dev.sevenrungs.jvminternals;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EscapeProbeTest {

  @Test
  void sumIsAlwaysZeroRegardlessOfWhatTheJitDoesWithIt() {
    // acc.x() accumulates +i and acc.y() accumulates -i over the same range [0, n), so their
    // sum is 0 whether or not C2 ever scalar-replaces the Point - this holds under -Xint,
    // -XX:TieredStopAtLevel=1, and -XX:-DoEscapeAnalysis just as much as under default flags.
    assertEquals(0, EscapeProbe.sum(0));
    assertEquals(0, EscapeProbe.sum(1));
    assertEquals(0, EscapeProbe.sum(1_000));
    assertEquals(0, EscapeProbe.sum(1_000_000));
  }
}
