package dev.sevenrungs.productionjvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The guru rung's cgroup scenarios, turned into real assertions instead of a read-and-believe shell
 * transcript. See {@link ContainerCgroup}'s class Javadoc for why this substitutes real cgroups for
 * {@code docker run} (Docker image pulls are blocked by this sandbox's egress policy). Skips rather
 * than fails when cgroup v1 isn't writable (not root, or a cgroup v2-only host), so the module
 * still builds green in a restricted environment.
 */
class ContainerCgroupTest {

  @Test
  @Timeout(30)
  void oneCpuAndHalfGigabyteMemoryPicksSerialGcAndQuartersTheHeap() throws Exception {
    assumeTrue(
        ContainerCgroup.cgroupsAvailable(), "cgroup v1 cpu/memory controllers not writable here");
    long memoryLimit = 512L * 1024 * 1024;

    try (var cgroup = ContainerCgroup.create("test-1cpu-512mb")) {
      cgroup.setCpuQuota(100_000, 100_000); // 1.0 CPU
      cgroup.setMemoryLimitBytes(memoryLimit);

      var result = cgroup.runJava(List.of("-cp", classpath(), probeClass()));
      assertEquals(0, result.exitCode(), "probe must exit cleanly; output: " + result.output());
      assertTrue(
          result.output().contains("processors=1"),
          "expected 1 processor; output: " + result.output());
      assertTrue(
          result.output().contains("collectors=Copy,MarkSweepCompact"),
          "expected SerialGC's bean names; output: " + result.output());
      // Runtime.maxMemory() reports the actually-usable heap, a few percent under the
      // -XX:MaxHeapSize=25%-of-limit flag value itself (generational layout/alignment overhead) -
      // confirmed empirically (129,761,280 vs. the flag's 134,217,728 for a 512 MB limit), so this
      // checks the ergonomic quarter-of-the-limit sizing within a tolerance, not exact equality.
      long expectedHeap = memoryLimit / 4;
      long actualHeap =
          Long.parseLong(
              result
                  .output()
                  .lines()
                  .filter(l -> l.startsWith("maxMemoryBytes="))
                  .findFirst()
                  .orElseThrow()
                  .substring("maxMemoryBytes=".length()));
      assertTrue(
          actualHeap > expectedHeap * 0.9 && actualHeap <= expectedHeap,
          "expected heap near 25% of the memory limit (" + expectedHeap + "), got " + actualHeap);
    }
  }

  @Test
  @Timeout(30)
  void oneAndAHalfCpuWithTwoGigabytesPicksG1() throws Exception {
    assumeTrue(
        ContainerCgroup.cgroupsAvailable(), "cgroup v1 cpu/memory controllers not writable here");

    try (var cgroup = ContainerCgroup.create("test-1.5cpu-2gb")) {
      cgroup.setCpuQuota(150_000, 100_000); // 1.5 CPUs, rounds up to 2
      cgroup.setMemoryLimitBytes(2L * 1024 * 1024 * 1024);

      var result = cgroup.runJava(List.of("-cp", classpath(), probeClass()));
      assertEquals(0, result.exitCode(), "probe must exit cleanly; output: " + result.output());
      assertTrue(
          result.output().contains("processors=2"),
          "expected 2 processors; output: " + result.output());
      assertTrue(
          result.output().contains("G1 Young Generation")
              && result.output().contains("G1 Old Generation"),
          "expected G1's bean names once enough memory is present; output: " + result.output());
    }
  }

  @Test
  @Timeout(30)
  void tightQuotaUnderRealLoadProducesRealThrottling() throws Exception {
    assumeTrue(
        ContainerCgroup.cgroupsAvailable(), "cgroup v1 cpu/memory controllers not writable here");

    try (var cgroup = ContainerCgroup.create("test-throttled")) {
      cgroup.setCpuQuota(20_000, 100_000); // 0.2 CPU - deliberately tight
      cgroup.setMemoryLimitBytes(512L * 1024 * 1024);

      // Four threads spinning for 3s guarantees demand far exceeding a 0.2-CPU quota.
      var result =
          cgroup.runJava(
              List.of("-cp", classpath(), "dev.sevenrungs.productionjvm.Spinner", "3", "4"));
      assertEquals(0, result.exitCode(), "spinner must exit cleanly; output: " + result.output());

      ContainerCgroup.CpuStat stat = cgroup.cpuStat();
      assertTrue(stat.nrPeriods() > 0, "expected at least one accounting period to have elapsed");
      assertTrue(
          stat.nrThrottled() > 0, "expected real throttling under a 0.2 CPU quota; stat=" + stat);
      assertTrue(stat.throttledTimeNanos() > 0, "expected nonzero throttled time; stat=" + stat);
    }
  }

  private static String classpath() {
    return System.getProperty("java.class.path");
  }

  private static String probeClass() {
    return "dev.sevenrungs.productionjvm.ErgonomicsProbe";
  }
}
