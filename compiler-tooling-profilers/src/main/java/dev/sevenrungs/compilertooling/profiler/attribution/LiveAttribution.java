package dev.sevenrungs.compilertooling.profiler.attribution;

import com.sun.tools.attach.VirtualMachine;
import dev.sevenrungs.compilertooling.profiler.AgentJar;
import dev.sevenrungs.compilertooling.profiler.attribution.SavingsAttributionReport.Capture;
import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.estimate.ArrayLengthSamples;
import dev.sevenrungs.jvminternals.footprint.estimate.LiveHistogram;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The attribution report for a JVM that is <em>already running</em> - no restart, no {@code
 * -javaagent}: attach, watch for a window, detach, and leave the application as it was found.
 *
 * <pre>
 *   java -cp ... LiveAttribution &lt;pid&gt; com.acme. 60
 * </pre>
 *
 * <ol>
 *   <li>read the target's layout flags ({@code jcmd VM.flags}) - the estimate's source
 *       configuration - and its classpath (Attach API system properties) for the size oracle;
 *   <li>start a JFR old-object recording ({@code jcmd JFR.start}) and attach {@code
 *       AllocationAgent} with {@code start:classfile:<prefix>};
 *   <li>after the window: {@code stop} the agent (original bytecode restored, counts frozen), ask
 *       it for its counts ({@code report:<file>}), take the live histogram ({@code jcmd
 *       GC.class_histogram}, a full GC), stop the recording;
 *   <li>on failure, the same cleanup still runs: agent stopped, recording stopped, detached.
 * </ol>
 *
 * <p>What a late attach can and can't see: the agent counts only allocations made during the
 * window, and JFR only samples those. That's no loss for data that turns over - sessions, caches
 * with eviction, request-scoped graphs - whose live instances were all reallocated during a long
 * enough window. It is a loss for data built once at startup: those classes stay unattributed
 * ({@code min(1, seen / live)} caps what the agent's sites get), and the report says so rather than
 * guessing. The estimate itself is unaffected: it works from the whole histogram.
 *
 * <p>Two things the running JVM can't give a late attach, both fixed at launch: a bigger JFR
 * old-object queue (256 samples by default) and small fixed TLABs. Expect a few hundred JFR samples
 * rather than thousands; arrays with fewer than 10 fall back to the uniform-padding estimate.
 */
public final class LiveAttribution {
  static final String RECORDING = "footprint-attribution";

  private LiveAttribution() {}

  /** What a window of observation produced, plus what the estimator needs to know. */
  public record LiveCapture(Capture capture, JvmMemoryConfig source, String classpath) {}

  public static void main(String[] args) {
    if (args.length < 3) {
      System.err.println(
          "usage: LiveAttribution <pid> <application-class-prefix> <window-seconds>");
      System.exit(2);
    }
    LiveCapture live =
        capture(Long.parseLong(args[0]), args[1], Duration.ofSeconds(Long.parseLong(args[2])));
    System.out.printf(
        "JVM %s watched for %ss, application prefix %s, running as %s%n%n",
        args[0], args[2], args[1], live.source());
    SavingsAttributionReport.print(
        SavingsAttributionReport.attributeAll(live.capture(), live.source(), live.classpath()));
  }

  /** Attaches to {@code pid}, watches allocations for {@code window}, and detaches. */
  public static LiveCapture capture(long pid, String prefix, Duration window) {
    String id = String.valueOf(pid);
    Path agent = AgentJar.build();
    try {
      Path report = Files.createTempFile("allocation-report-", ".txt");
      Path recording = Files.createTempFile("allocation-", ".jfr");
      VirtualMachine vm = VirtualMachine.attach(id);
      boolean recordingStarted = false;
      boolean agentStarted = false;
      try {
        JvmMemoryConfig source = detectConfig(jcmd(id, "VM.flags", "-all"));
        String classpath = classpathOf(vm.getSystemProperties());

        jcmd(
            id,
            "JFR.start",
            "name=" + RECORDING,
            "jdk.OldObjectSample#enabled=true",
            "jdk.OldObjectSample#stackTrace=true",
            "jdk.OldObjectSample#cutoff=0s");
        recordingStarted = true;
        vm.loadAgent(agent.toString(), "start:classfile:" + prefix);
        agentStarted = true;

        Thread.sleep(window);

        // close the window before reading it: stopped, the counts are frozen and consistent with
        // each other (read while running, the counters are sampled at slightly different moments
        // of a loop making millions of calls a second, and the 1:3 Order:LineItem ratio drifts)
        vm.loadAgent(agent.toString(), "stop");
        agentStarted = false;
        vm.loadAgent(agent.toString(), "report:" + report);
        // the histogram's full GC also clears JFR's dead samples before the recording ends
        LiveHistogram histogram = LiveHistogram.parse(jcmd(id, "GC.class_histogram"));
        jcmd(id, "JFR.stop", "name=" + RECORDING, "filename=" + recording);
        recordingStarted = false;

        Capture capture =
            new Capture(
                histogram,
                AllocationSites.parse(Files.readString(report)),
                JfrAllocationCallers.read(recording, prefix),
                ArrayLengthSamples.read(recording));
        return new LiveCapture(capture, source, classpath);
      } finally {
        if (agentStarted) {
          vm.loadAgent(agent.toString(), "stop");
        }
        if (recordingStarted) {
          jcmd(id, "JFR.stop", "name=" + RECORDING);
        }
        vm.detach();
        Files.deleteIfExists(report);
        Files.deleteIfExists(recording);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) { // AttachNotSupportedException, AgentLoadException, AgentInitialization
      throw new IllegalStateException("attaching to JVM " + pid + " failed", e);
    }
  }

  //      bool UseCompactObjectHeaders                  = true         {product lp64_product} ...
  private static final Pattern FLAG = Pattern.compile("^\\s*\\S+\\s+(\\w+)\\s+:?=\\s+(\\S+).*$");

  /**
   * The {@link JvmMemoryConfig} a JVM runs as, from its {@code VM.flags -all} output. Combinations
   * the enum doesn't model (compact headers and 16-byte alignment together, say) are refused rather
   * than approximated.
   */
  static JvmMemoryConfig detectConfig(String vmFlags) {
    Map<String, String> flags = new HashMap<>();
    for (String line : vmFlags.lines().toList()) {
      var m = FLAG.matcher(line);
      if (m.matches()) {
        flags.put(m.group(1), m.group(2));
      }
    }
    List<JvmMemoryConfig> matching = new ArrayList<>();
    if ("true".equals(flags.get("UseCompactObjectHeaders"))) {
      matching.add(JvmMemoryConfig.COMPACT_HEADERS);
    }
    if ("false".equals(flags.get("UseCompressedClassPointers"))) {
      matching.add(JvmMemoryConfig.NO_COMPRESSED_CLASS_POINTERS);
    }
    if ("false".equals(flags.get("UseCompressedOops"))) {
      matching.add(JvmMemoryConfig.NO_COMPRESSED_OOPS);
    }
    if (!"8".equals(flags.getOrDefault("ObjectAlignmentInBytes", "8"))) {
      if (!"16".equals(flags.get("ObjectAlignmentInBytes"))) {
        throw new IllegalStateException(
            "ObjectAlignmentInBytes=" + flags.get("ObjectAlignmentInBytes") + " isn't modeled");
      }
      matching.add(JvmMemoryConfig.ALIGNMENT_16);
    }
    if (!flags.containsKey("UseCompressedOops")) {
      throw new IllegalStateException("no layout flags in VM.flags output:\n" + vmFlags);
    }
    if (matching.size() > 1) {
      throw new IllegalStateException("layout flag combination isn't modeled: " + matching);
    }
    return matching.isEmpty() ? JvmMemoryConfig.DEFAULT : matching.getFirst();
  }

  /** The target's classpath with relative entries resolved against its working directory. */
  static String classpathOf(Properties target) {
    Path workingDir = Path.of(target.getProperty("user.dir", "."));
    List<String> entries = new ArrayList<>();
    for (String entry : target.getProperty("java.class.path", "").split(File.pathSeparator)) {
      if (!entry.isBlank()) {
        entries.add(workingDir.resolve(entry).normalize().toString());
      }
    }
    return String.join(File.pathSeparator, entries);
  }

  /** Runs {@code jcmd <pid> <command...>} and returns its output, failing on a non-zero exit. */
  static String jcmd(String pid, String... command) throws IOException, InterruptedException {
    List<String> line = new ArrayList<>();
    line.add(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString());
    line.add(pid);
    line.addAll(List.of(command));
    ProcessBuilder builder = new ProcessBuilder(line).redirectErrorStream(true);
    builder.environment().remove("JAVA_TOOL_OPTIONS"); // its banner would pollute the output
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
      process.destroyForcibly();
      throw new IOException("jcmd " + String.join(" ", command) + " failed:\n" + output);
    }
    return output;
  }
}
