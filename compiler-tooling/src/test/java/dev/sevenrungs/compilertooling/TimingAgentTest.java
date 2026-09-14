package dev.sevenrungs.compilertooling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Forks a real child JVM with {@code -javaagent:<built-agent-jar>=<prefix>}, the same pattern
 * {@code MappedRingTest}/{@code GcMultiCollectorSmokeTest} use for other real-process proof, since
 * an agent can only be attached at JVM launch against an actual jar file with a {@code
 * Premain-Class} manifest entry - built here from {@code target/classes} directly (tests run before
 * the {@code package} phase produces the module's real jar).
 */
class TimingAgentTest {

  @Test
  @Timeout(30)
  void instrumentsEveryMethodAndPreservesBehavior() throws Exception {
    Path agentJar = buildAgentJar();
    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("java.class.path");

    Process process =
        new ProcessBuilder(
                javaBin,
                "-javaagent:"
                    + agentJar
                    + "=dev.sevenrungs.compilertooling.TimingAgentTargetFixture",
                "-cp",
                classpath,
                "dev.sevenrungs.compilertooling.TimingAgentTargetFixture")
            .redirectErrorStream(true)
            .start();

    String output = new String(process.getInputStream().readAllBytes());
    boolean finished = process.waitFor(20, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      fail("target process did not finish within 20s; output so far: " + output);
    }
    assertEquals(0, process.exitValue(), "target process must exit cleanly; output: " + output);

    // ClassFileTransformer.transform's className is the JVM's internal (slash-separated) form,
    // e.g. "com/acme/orders/Foo" - the same form the artifact's own comment shows
    // ("Recorder.enter(\"com/acme/orders/Foo.bar\")"); TimingAgent uses it as-is rather than
    // reformatting it, so the recorded site name keeps that slash form too.
    assertTrue(
        output.contains("ENTER dev/sevenrungs/compilertooling/TimingAgentTargetFixture.main"),
        "expected an ENTER event for main(); output: " + output);
    assertTrue(
        output.contains("ENTER dev/sevenrungs/compilertooling/TimingAgentTargetFixture.add"),
        "expected an ENTER event for add(); output: " + output);
    assertTrue(
        output.contains("result=5"),
        "instrumentation must not change the target's own behavior; output: " + output);
  }

  /** Packages this module's already-compiled classes into a real Premain-Class agent jar. */
  private static Path buildAgentJar() throws IOException {
    Path classesDir = Path.of("target", "classes");
    Path agentJar = Files.createTempFile("timing-agent-", ".jar");

    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest
        .getMainAttributes()
        .putValue("Premain-Class", "dev.sevenrungs.compilertooling.TimingAgent");
    manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");

    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(agentJar), manifest)) {
      for (String className :
          new String[] {
            "dev/sevenrungs/compilertooling/TimingAgent.class",
            "dev/sevenrungs/compilertooling/TimingAgent$Transformer.class",
            "dev/sevenrungs/compilertooling/Recorder.class"
          }) {
        Path classFile = classesDir.resolve(className);
        jos.putNextEntry(new JarEntry(className));
        try (InputStream in = Files.newInputStream(classFile)) {
          in.transferTo(jos);
        }
        jos.closeEntry();
      }
    }
    return agentJar;
  }
}
