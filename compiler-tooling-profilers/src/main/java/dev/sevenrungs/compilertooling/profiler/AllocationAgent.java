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
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public final class AllocationAgent {
  private AllocationAgent() {}

  public static void premain(String agentArgs, Instrumentation inst) {
    if (agentArgs == null || !agentArgs.contains(":")) {
      throw new IllegalArgumentException(
          "usage: -javaagent:profilers.jar=<classfile|asm|bytebuddy>:<class-name-prefix>");
    }
    int sep = agentArgs.indexOf(':');
    Instrumenter instrumenter = forName(agentArgs.substring(0, sep));
    String prefix = agentArgs.substring(sep + 1);

    inst.addTransformer(
        new ClassFileTransformer() {
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
        },
        true);

    Runtime.getRuntime().addShutdownHook(new Thread(AllocationRecorder::report));
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
