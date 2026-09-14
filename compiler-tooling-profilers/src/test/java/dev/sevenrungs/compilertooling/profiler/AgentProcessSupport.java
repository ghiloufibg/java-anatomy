package dev.sevenrungs.compilertooling.profiler;

// Shared test support: real child-JVM verification for the three Instrumenter implementations.
//
// Earlier drafts of these tests defined an instrumenter's output class in-process (a fresh
// ClassLoader + defineClass) and hit a reproducible VerifyError ("Operand stack overflow") that
// looked, for a while, like it depended on the surrounding JUnit execution machinery - it
// persisted across renaming the defined class, forcing eager linking via
// Class.forName(name, true, loader), disabling Surefire's useManifestOnlyJar fork mode, and even
// swapping Surefire out entirely for the bare JUnit Platform Launcher API. Forking a real child
// JVM per implementation (below) was originally adopted to sidestep that suspected in-process
// hazard. Once these tests ran that way, the actual cause surfaced immediately: it was
// ByteBuddyInstrumenter alone, deterministically, in every environment including a genuinely
// fresh `java` process - see its own source for the real bug (a missing AsmVisitorWrapper
// mergeWriter override, so Byte Buddy never recomputed max_stack for the extra ldc+invokestatic
// this project's wrapper inserts). ClassFileApiInstrumenter and AsmInstrumenter never had this
// problem, in-process or otherwise. The child-JVM design stays regardless: it is the more
// faithful way to test a java.lang.instrument agent's real usage path than any in-process
// defineClass() trick, independent of the bug that first motivated it.
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
import java.util.stream.Stream;

final class AgentProcessSupport {
  private AgentProcessSupport() {}

  /**
   * Runs {@link WorkloadRunnerFixture} in a real child JVM under {@link AllocationAgent} with the
   * given implementation, returning the captured stdout (the workload's own output plus
   * AllocationAgent's shutdown-hook report). A clean exit is itself the JVM verifier's pass/fail
   * signal (JVMS §4.10): a VerifyError on class definition aborts the child's startup outright.
   */
  static String runWorkloadUnderAgent(String implementation)
      throws IOException, InterruptedException {
    Path agentJar = buildAgentJar();
    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("java.class.path");

    Process process =
        new ProcessBuilder(
                javaBin,
                "-javaagent:"
                    + agentJar
                    + "="
                    + implementation
                    + ":dev.sevenrungs.compilertooling.profiler.Workload",
                "-cp",
                classpath,
                "dev.sevenrungs.compilertooling.profiler.WorkloadRunnerFixture")
            .redirectErrorStream(true)
            .start();

    String output = new String(process.getInputStream().readAllBytes());
    boolean finished = process.waitFor(20, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      fail("[" + implementation + "] did not finish within 20s; output so far: " + output);
    }
    if (process.exitValue() != 0) {
      fail("[" + implementation + "] must exit cleanly; output: " + output);
    }
    return output;
  }

  /** Packages this module's already-compiled profiler classes into a real Premain-Class jar. */
  static Path buildAgentJar() throws IOException {
    Path classesDir = Path.of("target", "classes");
    Path packageDir =
        classesDir.resolve(Path.of("dev", "sevenrungs", "compilertooling", "profiler"));
    Path agentJar = Files.createTempFile("allocation-agent-", ".jar");

    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest
        .getMainAttributes()
        .putValue("Premain-Class", "dev.sevenrungs.compilertooling.profiler.AllocationAgent");
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
