package dev.sevenrungs.compilertooling.profiler;

// Builds a real Premain-Class jar for AllocationAgent from wherever this module's classes live,
// so a child JVM can be started with `-javaagent:` without `mvn package` having produced the
// shaded profilers.jar first. Used by the tests' AgentProcessSupport and by attribution's CLI.
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

public final class AgentJar {
  private AgentJar() {}

  /**
   * An agent jar for {@link AllocationAgent}: the classes' own jar if they already came from one,
   * otherwise a fresh temp jar packaging this package's compiled classes (e.g. {@code
   * target/classes}). ASM and Byte Buddy are not bundled - they come from the child's classpath.
   */
  public static Path build() {
    try {
      Path source =
          Path.of(
              AllocationAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      return Files.isDirectory(source) ? packageDirectory(source) : source;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Path packageDirectory(Path classesDir) throws IOException {
    Path packageDir = classesDir.resolve(AllocationAgent.class.getPackageName().replace('.', '/'));
    Path agentJar = Files.createTempFile("allocation-agent-", ".jar");

    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("Premain-Class", AllocationAgent.class.getName());
    manifest.getMainAttributes().putValue("Agent-Class", AllocationAgent.class.getName());
    manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");

    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(agentJar), manifest);
        Stream<Path> classFiles = Files.walk(packageDir)) {
      for (Path classFile : classFiles.filter(p -> p.toString().endsWith(".class")).toList()) {
        String entryName = classesDir.relativize(classFile).toString().replace('\\', '/');
        jos.putNextEntry(new JarEntry(entryName));
        try (InputStream in = Files.newInputStream(classFile)) {
          in.transferTo(jos);
        }
        jos.closeEntry();
      }
    }
    return agentJar;
  }
}
