package dev.sevenrungs.jvminternals.footprint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Forks a child JVM under one {@link JvmMemoryConfig}: object-layout flags are fixed at launch, so
 * every "what would this flag do" question in this package is answered by a separate JVM.
 *
 * <p>Every child gets the same heap and collector ({@code -XX:+UseSerialGC}, whose post-GC "used"
 * is exactly the compacted bytes), so the configuration's own flags are the only variable.
 */
public final class ChildJvm {
  static final String HEAP = "-Xmx2g";

  private ChildJvm() {}

  /**
   * Runs {@code mainClass} with {@code args} under {@code config}, on this JVM's classpath plus
   * {@code extraClasspath} (may be empty), and returns its combined stdout/stderr.
   */
  public static String run(
      JvmMemoryConfig config, String extraClasspath, Class<?> mainClass, String... args) {
    String classpath = System.getProperty("java.class.path");
    if (!extraClasspath.isEmpty()) {
      classpath += System.getProperty("path.separator") + extraClasspath;
    }
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add(HEAP);
    command.add("-XX:+UseSerialGC");
    // lets JOL self-attach for Instrumentation.getObjectSize - exact sizes under every header mode
    command.add("-Djdk.attach.allowAttachSelf=true");
    command.add("--add-opens");
    command.add("java.base/java.lang=ALL-UNNAMED");
    command.addAll(config.flags());
    command.add("-cp");
    command.add(classpath);
    command.add(mainClass.getName());
    command.addAll(List.of(args));
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IllegalStateException(
            mainClass.getSimpleName() + " failed under " + config + ":\n" + output);
      }
      return output;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** The first output line starting with {@code prefix}, or a failure quoting the whole output. */
  public static String lineStartingWith(String output, String prefix) {
    return output
        .lines()
        .filter(l -> l.startsWith(prefix))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no '" + prefix + "' line in:\n" + output));
  }
}
