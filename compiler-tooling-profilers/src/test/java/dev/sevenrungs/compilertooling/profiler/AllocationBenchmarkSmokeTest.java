package dev.sevenrungs.compilertooling.profiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs {@link AllocationBenchmarks} for real through JMH's own {@code Main} entry point, with the
 * shortest possible profile, in a real forked child JVM - not an in-process call. JMH's own
 * per-benchmark {@code @Fork(1)} already forks a genuinely fresh child JVM for every benchmark
 * method, so driving the real benchmark class through the real JMH harness - just with a minimal
 * profile - both smoke-tests correctness and matches how {@link AllocationAgentTest} verifies the
 * agent path. See {@link AgentProcessSupport}'s Javadoc for why every check in this phase's tests
 * forks real JVMs rather than defining instrumented classes in-process.
 */
class AllocationBenchmarkSmokeTest {

  @Test
  @Timeout(120)
  void allFourVariantsRunCleanlyUnderARealMinimalJmhPass() throws Exception {
    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("java.class.path");

    Process process =
        new ProcessBuilder(
                javaBin,
                "-cp",
                classpath,
                "org.openjdk.jmh.Main",
                "AllocationBenchmarks",
                "-f",
                "1",
                "-wi",
                "1",
                "-i",
                "1",
                "-w",
                "200ms",
                "-r",
                "200ms")
            .redirectErrorStream(true)
            .start();

    String output = new String(process.getInputStream().readAllBytes());
    boolean finished = process.waitFor(100, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      fail("JMH run did not finish within 100s; output so far: " + output);
    }
    assertEquals(0, process.exitValue(), "JMH run must exit cleanly; output: " + output);

    for (String benchmark : new String[] {"baseline", "classFileApi", "asm", "byteBuddy"}) {
      assertTrue(
          output.contains("AllocationBenchmarks." + benchmark),
          "expected a result line for " + benchmark + "; output: " + output);
    }
  }
}
