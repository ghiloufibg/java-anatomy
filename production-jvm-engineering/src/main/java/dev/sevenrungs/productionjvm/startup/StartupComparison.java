package dev.sevenrungs.productionjvm.startup;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Trains, then times, {@link StartupWorkload} under every {@link ClassSharingMode} in separate
 * child JVMs and prints the startup time, archive hit rate and Metaspace each one gets.
 *
 * <p>The workload is packaged into a real JAR first: CDS and AOT archives refuse a classpath that
 * contains a directory ("Cannot have non-empty directory in paths"), because a directory can't be
 * validated against the archive the way a JAR's size and mtime can. The same JAR is used for
 * training and measuring - rebuild it and the archive is stale, which strict mode catches.
 *
 * <p>{@code args[0]}, optional, is the number of measured runs per mode (default 5):
 *
 * <pre>
 *   mvn -q -pl production-jvm-engineering compile exec:java \
 *       -Dexec.mainClass=dev.sevenrungs.productionjvm.startup.StartupComparison
 * </pre>
 */
public final class StartupComparison {
  private static final String PREFIX = "STARTUP ";

  private StartupComparison() {}

  public static void main(String[] args) throws IOException {
    int runs = args.length > 0 ? Integer.parseInt(args[0]) : 5;
    Map<ClassSharingMode, StartupResult> results =
        measureAll(Files.createTempDirectory("cds-"), runs);
    StartupResult baseline = results.get(ClassSharingMode.OFF);
    System.out.println(
        "| Mode | Best wall | Median wall | Median in main | From archive | Metaspace used"
            + " | Archive | Wall vs CDS off |");
    System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|");
    for (StartupResult r : results.values()) {
      System.out.printf(
          "| %s | %d ms | %d ms | %d ms | %d / %d (%.0f%%) | %.1f MB | %s | %s |%n",
          r.mode().description(),
          r.bestWallMillis(),
          r.medianWallMillis(),
          r.medianMainMillis(),
          r.sharedClasses(),
          r.loadedClasses(),
          100 * r.sharedFraction(),
          (r.metaspaceUsedKb() + r.classSpaceUsedKb()) / 1024.0,
          r.archiveBytes() == 0 ? "-" : "%.1f MB".formatted(r.archiveBytes() / 1_048_576.0),
          r == baseline
              ? "baseline"
              : "%+.0f%%"
                  .formatted(
                      100.0
                          * (r.medianWallMillis() - baseline.medianWallMillis())
                          / baseline.medianWallMillis()));
    }
  }

  /** Packages the workload into {@code workDir} and measures every mode against the same JAR. */
  public static Map<ClassSharingMode, StartupResult> measureAll(Path workDir, int runs) {
    Path jar = packageWorkload(workDir);
    Map<ClassSharingMode, StartupResult> results = new EnumMap<>(ClassSharingMode.class);
    for (ClassSharingMode mode : ClassSharingMode.values()) {
      results.put(mode, measure(mode, jar, workDir, runs));
    }
    return results;
  }

  /**
   * Runs the training step if {@code mode} has one, then {@code runs} timed runs and one logged.
   */
  public static StartupResult measure(ClassSharingMode mode, Path jar, Path workDir, int runs) {
    if (mode.needsTraining()) {
      run(jar, mode.trainingFlags(workDir));
    }
    long[] wall = new long[runs];
    long[] main = new long[runs];
    Map<String, String> last = Map.of();
    for (int i = 0; i < runs; i++) {
      long start = System.nanoTime();
      last = run(jar, mode.runFlags(workDir));
      wall[i] = (System.nanoTime() - start) / 1_000_000;
      main[i] = Long.parseLong(last.get("mainMillis"));
    }

    // a separate run for the class-load log, so logging I/O doesn't pollute the timings above
    Path log = workDir.resolve(mode.name().toLowerCase() + "-class-load.log");
    List<String> logged = new ArrayList<>(mode.runFlags(workDir));
    logged.add("-Xlog:class+load=info:file=" + log);
    run(jar, logged);
    List<String> loads = readLines(log).stream().filter(l -> l.contains(" source: ")).toList();

    Arrays.sort(wall);
    Arrays.sort(main);
    return new StartupResult(
        mode,
        wall[0],
        wall[runs / 2],
        main[runs / 2],
        loads.size(),
        (int) loads.stream().filter(StartupComparison::fromArchive).count(),
        loads.stream()
            .filter(l -> l.contains(" " + StartupWorkload.class.getName() + " source: "))
            .anyMatch(StartupComparison::fromArchive),
        Long.parseLong(last.get("metaspaceUsedKb")),
        Long.parseLong(last.get("classSpaceUsedKb")),
        mode.needsTraining() ? size(mode.archive(workDir)) : 0);
  }

  private static boolean fromArchive(String classLoadLine) {
    return classLoadLine.contains("source: shared objects file");
  }

  /** Forks the workload with {@code flags}; returns its parsed {@code STARTUP} line. */
  static Map<String, String> run(Path jar, List<String> flags) {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(flags);
    command.add("-cp");
    command.add(jar.toString());
    command.add(StartupWorkload.class.getName());
    try {
      var builder = new ProcessBuilder(command).redirectErrorStream(true);
      // an inherited JAVA_TOOL_OPTIONS would add work to every run; keep the flag the only variable
      builder.environment().remove("JAVA_TOOL_OPTIONS");
      Process process = builder.start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IllegalStateException("workload failed with " + flags + ":\n" + output);
      }
      String line =
          output
              .lines()
              .filter(l -> l.startsWith(PREFIX))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException("no STARTUP line with " + flags + ":\n" + output));
      Map<String, String> fields = new HashMap<>();
      for (String pair : line.substring(PREFIX.length()).trim().split("\\s+")) {
        int eq = pair.indexOf('=');
        fields.put(pair.substring(0, eq), pair.substring(eq + 1));
      }
      return fields;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Writes {@code workDir/app.jar} holding just the workload's class file. */
  static Path packageWorkload(Path workDir) {
    String entry = StartupWorkload.class.getName().replace('.', '/') + ".class";
    Path jar = workDir.resolve("app.jar");
    try (InputStream classFile = StartupWorkload.class.getResourceAsStream("/" + entry);
        var out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry(entry));
      classFile.transferTo(out);
      out.closeEntry();
      return jar;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
