package dev.sevenrungs.productionjvm;

// The JVM in a container: ergonomics you must be able to predict before the pager goes off.
// Since JDK 10 (JDK-8146115) HotSpot reads cgroup limits (v2 since JDK 15, JDK-8230305); what it
// derives from them decides your GC, your heap, and your thread pools.
//
// This class demonstrates that with real cgroups instead of `docker run`. Docker itself is
// available in this sandbox, but pulling any image is not: the registry redirects to
// production.cloudfront.docker.com, and the sandbox's own egress policy hard-blocks that host
// (confirmed via the agent proxy's own status endpoint - a policy denial, not a transient error).
// This is a legitimate substitute, not a workaround of convenience: HotSpot's own container
// detection (src/hotspot/os/linux/cgroupSubsystem_linux.cpp) only ever reads the calling
// process's cgroup files - it has no dependency on that process actually running inside a real
// container, Docker or otherwise, and Docker's own container support is itself implemented as
// exactly this: place the process in a cgroup with the requested limits before it execs.
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class ContainerCgroup implements AutoCloseable {

  private final Path cpuDir;
  private final Path memoryDir;

  private ContainerCgroup(Path cpuDir, Path memoryDir) {
    this.cpuDir = cpuDir;
    this.memoryDir = memoryDir;
  }

  /** Creates a fresh cgroup v1 hierarchy under the cpu and memory controllers. */
  public static ContainerCgroup create(String name) throws IOException {
    Path cpuDir = Path.of("/sys/fs/cgroup/cpu", name);
    Path memoryDir = Path.of("/sys/fs/cgroup/memory", name);
    Files.createDirectories(cpuDir);
    Files.createDirectories(memoryDir);
    return new ContainerCgroup(cpuDir, memoryDir);
  }

  /** True when this process can actually create and populate cgroups here (needs root). */
  public static boolean cgroupsAvailable() {
    Path cpuRoot = Path.of("/sys/fs/cgroup/cpu");
    Path memoryRoot = Path.of("/sys/fs/cgroup/memory");
    return Files.isDirectory(cpuRoot)
        && Files.isWritable(cpuRoot)
        && Files.isDirectory(memoryRoot)
        && Files.isWritable(memoryRoot);
  }

  /** Mirrors {@code docker run --cpus}: a CFS quota/period pair, e.g. (100000, 100000) = 1 CPU. */
  public void setCpuQuota(long quotaMicros, long periodMicros) throws IOException {
    Files.writeString(cpuDir.resolve("cpu.cfs_period_us"), Long.toString(periodMicros));
    Files.writeString(cpuDir.resolve("cpu.cfs_quota_us"), Long.toString(quotaMicros));
  }

  /** Mirrors {@code docker run --memory}. */
  public void setMemoryLimitBytes(long bytes) throws IOException {
    Files.writeString(memoryDir.resolve("memory.limit_in_bytes"), Long.toString(bytes));
  }

  /**
   * Runs {@code java} confined to this cgroup and returns its exit code and captured output.
   *
   * <p>The technique: launch a shell, have it write its own pid into both controllers'
   * cgroup.procs, then {@code exec} into java in place. This is not an ad-hoc trick - it is exactly
   * what cgexec, systemd-run and runc themselves do, and it is the only version that is reliably
   * race-free: a process moved into a cgroup by someone else *after* being forked can be re-homed
   * by another process-management layer before it gets there (observed directly in this sandbox,
   * which runs its own subprocess supervisor); a process that puts itself into the cgroup and then
   * execs in its own image never leaves that window open at all.
   */
  public ProcessResult runJava(List<String> javaArgs) throws IOException, InterruptedException {
    Process process = startJava(javaArgs);
    String output = new String(process.getInputStream().readAllBytes());
    int exitCode = process.waitFor();
    return new ProcessResult(exitCode, output);
  }

  /**
   * Same confinement as {@link #runJava}, but returns the live {@link Process} immediately instead
   * of blocking for it to finish - for a caller (such as the exercise's flight-deck test) that
   * needs to interact with the confined process (e.g. make HTTP requests against a service it just
   * started) while it is still running.
   */
  public Process startJava(List<String> javaArgs) throws IOException {
    String quotedArgs =
        javaArgs.stream()
            .map(a -> "'" + a.replace("'", "'\\''") + "'")
            .collect(Collectors.joining(" "));
    String script =
        "echo $$ > "
            + cpuDir.resolve("cgroup.procs")
            + " && echo $$ > "
            + memoryDir.resolve("cgroup.procs")
            + " && exec java "
            + quotedArgs;
    return new ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start();
  }

  public record ProcessResult(int exitCode, String output) {}

  /**
   * Reads the CFS bandwidth controller's own throttling counters (JVMS says nothing; this is pure
   * Linux CFS: {@code kernel/sched/core.c}'s bandwidth accounting).
   */
  public CpuStat cpuStat() throws IOException {
    long nrPeriods = 0, nrThrottled = 0, throttledTimeNanos = 0;
    for (String line : Files.readAllLines(cpuDir.resolve("cpu.stat"))) {
      String[] kv = line.split(" ");
      switch (kv[0]) {
        case "nr_periods" -> nrPeriods = Long.parseLong(kv[1]);
        case "nr_throttled" -> nrThrottled = Long.parseLong(kv[1]);
        case "throttled_time" -> throttledTimeNanos = Long.parseLong(kv[1]);
        default -> {}
      }
    }
    return new CpuStat(nrPeriods, nrThrottled, throttledTimeNanos);
  }

  public record CpuStat(long nrPeriods, long nrThrottled, long throttledTimeNanos) {}

  /**
   * Best-effort: removing a cgroup directory fails with EBUSY while any process is still a member
   * of it (via cgroup.procs), which can briefly outlast a killed child even after {@code waitFor}
   * returns. Swallowing that here, rather than letting it throw, keeps a stray leftover cgroup from
   * masking whatever real assertion failure is already propagating out of a test.
   */
  @Override
  public void close() {
    try {
      Files.deleteIfExists(cpuDir);
    } catch (IOException ignored) {
      // best-effort cleanup; see class Javadoc above
    }
    try {
      Files.deleteIfExists(memoryDir);
    } catch (IOException ignored) {
      // best-effort cleanup; see class Javadoc above
    }
  }

  /**
   * Runs the three scenarios from the guru rung end to end and prints what the artifact's {@code
   * docker run} commands would have shown, using {@link ErgonomicsProbe} as the confined process
   * instead of {@code -version}/{@code -XX:+PrintFlagsFinal}, since a real running JVM reporting
   * its own {@code Runtime}/{@code ManagementFactory} view is more convincing (and more
   * idiomatically Java) than grepping flag-dump text.
   */
  public static void main(String[] args) throws Exception {
    if (!cgroupsAvailable()) {
      System.out.println(
          "cgroup v1 cpu/memory controllers not writable here (need root) - skipping");
      return;
    }
    String probeCp = System.getProperty("java.class.path");

    try (var oneCpu512Mb = create("demo-1cpu-512mb")) {
      oneCpu512Mb.setCpuQuota(100_000, 100_000); // 1.0 CPU
      oneCpu512Mb.setMemoryLimitBytes(512L * 1024 * 1024);
      System.out.println("=== 1 CPU / 512 MB (docker run --cpus=1 --memory=512m) ===");
      System.out.println(
          oneCpu512Mb
              .runJava(List.of("-cp", probeCp, "dev.sevenrungs.productionjvm.ErgonomicsProbe"))
              .output());
    }

    try (var oneHalfCpu2Gb = create("demo-1.5cpu-2gb")) {
      oneHalfCpu2Gb.setCpuQuota(150_000, 100_000); // 1.5 CPUs, rounds up to 2
      oneHalfCpu2Gb.setMemoryLimitBytes(2L * 1024 * 1024 * 1024);
      System.out.println("=== 1.5 CPU / 2 GB (docker run --cpus=1.5 --memory=2g) ===");
      System.out.println(
          oneHalfCpu2Gb
              .runJava(List.of("-cp", probeCp, "dev.sevenrungs.productionjvm.ErgonomicsProbe"))
              .output());
    }

    try (var throttled = create("demo-throttled")) {
      throttled.setCpuQuota(20_000, 100_000); // 0.2 CPU - deliberately tight
      throttled.setMemoryLimitBytes(512L * 1024 * 1024);
      System.out.println("=== 0.2 CPU under real load (the CPU-quota-throttling trap) ===");
      // Four threads spinning on one-fifth of a CPU's worth of quota guarantees real throttling.
      throttled.runJava(List.of("-cp", probeCp, "dev.sevenrungs.productionjvm.Spinner", "3", "4"));
      CpuStat stat = throttled.cpuStat();
      System.out.printf(
          "cpu.stat: nr_periods=%d nr_throttled=%d throttled_time=%.3fs%n",
          stat.nrPeriods(), stat.nrThrottled(), stat.throttledTimeNanos() / 1e9);
    }
  }
}
