package dev.sevenrungs.compilertooling.profiler;

// A real java.lang.instrument agent selecting one of the three Instrumenter implementations by
// its agentArgs, so the exercise's "run every output class through a verifier" claim is provable
// with a real -javaagent run: a VerifyError on class definition would abort the target JVM's
// startup outright, so a clean exit from a run under each implementation *is* the verifier's
// pass/fail signal (JVMS §4.10).
// Run:  java -javaagent:profilers.jar=asm:dev.sevenrungs.compilertooling.profiler.Workload -cp ...
//       (impl is one of classfile, asm, bytebuddy; the part after ":" is the class-name prefix
//       to instrument - required, so this never tries to rewrite every loaded class including
//       the JDK's own bootstrap classes).
// Or attach to a JVM that is already running - see agentmain.
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

public final class AllocationAgent {
  /** The transformer a runtime attach installed, so a later "stop" can take it out again. */
  private static ClassFileTransformer attached;

  /** The prefix that transformer instruments: the classes "stop" has to restore. */
  private static String attachedPrefix;

  /**
   * The Instrumentation "start" was given. Every loadAgent call gets a fresh instance, and a
   * transformer can only be removed through the one it was added to - removing it through the
   * "stop" call's own instance silently does nothing, and retransforming through it would run the
   * still-registered transformer again (found by LiveAttributionTest: counts kept climbing).
   */
  private static Instrumentation attachedInstrumentation;

  private AllocationAgent() {}

  public static void premain(String agentArgs, Instrumentation inst) {
    if (agentArgs == null || !agentArgs.contains(":")) {
      throw new IllegalArgumentException(
          "usage: -javaagent:profilers.jar=<classfile|asm|bytebuddy>:<class-name-prefix>");
    }
    int sep = agentArgs.indexOf(':');
    inst.addTransformer(
        transformer(forName(agentArgs.substring(0, sep)), agentArgs.substring(sep + 1)), true);
    Runtime.getRuntime().addShutdownHook(new Thread(AllocationRecorder::report));
  }

  /**
   * Runtime attach (Attach API {@code VirtualMachine.loadAgent}), one command per attach:
   *
   * <ul>
   *   <li>{@code start:<impl>:<prefix>} - install the transformer and retransform every already
   *       loaded class matching the prefix. Counting starts now: allocations made before the attach
   *       were never counted and never will be. Use {@code classfile}: the target application has
   *       no ASM or Byte Buddy on its classpath, the JDK's ClassFile API is always there.
   *   <li>{@code report:<file>} - write the counts so far to {@code file}, one {@code site = count}
   *       line each, the same format as the shutdown report - never to the application's stdout.
   *   <li>{@code stop} - remove the transformer and retransform again, restoring the original
   *       bytecode, so the application is left as it was found.
   * </ul>
   *
   * <p>Retransformation may only change method bodies, never add fields or methods (JVMTI
   * RetransformClasses): inserting an {@code ldc} and an {@code invokestatic} before each
   * allocation is exactly that. Methods already running keep their old code until next called.
   */
  public static synchronized void agentmain(String agentArgs, Instrumentation inst)
      throws Exception {
    String[] parts = agentArgs == null ? new String[] {""} : agentArgs.split(":", 3);
    switch (parts[0]) {
      case "start" -> {
        if (attached != null) {
          throw new IllegalStateException("already started; send stop first");
        }
        if (parts.length != 3) {
          throw new IllegalArgumentException("usage: start:<classfile|asm|bytebuddy>:<prefix>");
        }
        attached = transformer(forName(parts[1]), parts[2]);
        attachedPrefix = parts[2];
        attachedInstrumentation = inst;
        inst.addTransformer(attached, true);
        retransform(inst, attachedPrefix);
      }
      case "report" -> {
        List<String> lines = new ArrayList<>();
        AllocationRecorder.snapshot().forEach((site, count) -> lines.add(site + " = " + count));
        Files.write(Path.of(agentArgs.substring("report:".length())), lines);
      }
      case "stop" -> {
        if (attached != null) {
          if (!attachedInstrumentation.removeTransformer(attached)) {
            throw new IllegalStateException("the start transformer was already gone");
          }
          attached = null;
          // no transformer left: back to the original bytecode
          retransform(attachedInstrumentation, attachedPrefix);
          attachedInstrumentation = null;
        }
      }
      default ->
          throw new IllegalArgumentException(
              "unknown command '"
                  + agentArgs
                  + "': use start:<impl>:<prefix>, report:<file>, stop");
    }
  }

  /**
   * Retransforms every loaded class whose name starts with {@code prefix}, skipping this agent's
   * own classes, hidden classes and unmodifiable ones.
   */
  private static void retransform(Instrumentation inst, String prefix)
      throws UnmodifiableClassException {
    List<Class<?>> matching = new ArrayList<>();
    for (Class<?> c : inst.getAllLoadedClasses()) {
      boolean ours = c.getName().startsWith(AllocationAgent.class.getPackageName());
      if (c.getName().startsWith(prefix) && !ours && !c.isHidden() && inst.isModifiableClass(c)) {
        matching.add(c);
      }
    }
    if (!matching.isEmpty()) {
      inst.retransformClasses(matching.toArray(Class<?>[]::new));
    }
  }

  private static ClassFileTransformer transformer(Instrumenter instrumenter, String prefix) {
    return new ClassFileTransformer() {
      @Override
      public byte[] transform(
          Module module,
          ClassLoader loader,
          String className,
          Class<?> classBeingRedefined,
          ProtectionDomain domain,
          byte[] classfileBuffer) {
        if (className == null || !className.replace('/', '.').startsWith(prefix)) {
          return null;
        }
        return instrumenter.instrument(className, classfileBuffer);
      }
    };
  }

  private static Instrumenter forName(String name) {
    return switch (name) {
      case "classfile" -> new ClassFileApiInstrumenter();
      case "asm" -> new AsmInstrumenter();
      case "bytebuddy" -> new ByteBuddyInstrumenter();
      default ->
          throw new IllegalArgumentException(
              "unknown instrumenter '" + name + "': use classfile, asm, or bytebuddy");
    };
  }
}
