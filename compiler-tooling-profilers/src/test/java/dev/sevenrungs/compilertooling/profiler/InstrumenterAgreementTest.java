package dev.sevenrungs.compilertooling.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The exercise's "same output, three code bases" property, actually checked: all three {@link
 * Instrumenter} implementations, run as real agents against a real child JVM (see {@link
 * AgentProcessSupport}'s Javadoc for why this test forks real processes rather than defining
 * instrumented classes in-process), must report identical per-site allocation counts.
 */
class InstrumenterAgreementTest {

  private static final Pattern REPORT_LINE = Pattern.compile("^(\\S+) = (\\d+)$");

  @Test
  @Timeout(60)
  void allThreeImplementationsReportIdenticalCounts() throws Exception {
    Map<String, Long> classFileCounts =
        countsFrom(AgentProcessSupport.runWorkloadUnderAgent("classfile"));
    Map<String, Long> asmCounts = countsFrom(AgentProcessSupport.runWorkloadUnderAgent("asm"));
    Map<String, Long> byteBuddyCounts =
        countsFrom(AgentProcessSupport.runWorkloadUnderAgent("bytebuddy"));

    assertFalse(classFileCounts.isEmpty(), "expected at least one recorded allocation site");
    assertEquals(classFileCounts, asmCounts, "ClassFile API and ASM must agree");
    assertEquals(classFileCounts, byteBuddyCounts, "ClassFile API and Byte Buddy must agree");
  }

  /** Parses {@code AllocationRecorder.report()}'s "site = count" lines out of process output. */
  private static Map<String, Long> countsFrom(String output) {
    Map<String, Long> counts = new TreeMap<>();
    for (String line : output.split("\\R")) {
      Matcher m = REPORT_LINE.matcher(line.strip());
      if (m.matches()) {
        counts.put(m.group(1), Long.parseLong(m.group(2)));
      }
    }
    return counts;
  }
}
