package dev.sevenrungs.compilertooling.profiler;

// A real hazard discovered while writing InstrumenterAgreementTest and AllocationBenchmarks:
// defining an instrumented class in-process, in a custom ClassLoader, under the SAME
// fully-qualified name as the real, unmodified Workload class that ALSO sits right there on this
// module's own classpath, threw a VerifyError ("Operand stack overflow") that the byte-for-byte
// identical bytes never hit when defined under a fresh, unused name - and that a real
// -javaagent run against the real name (AllocationAgentTest, a genuinely separate child JVM
// process where the original class is never separately resolvable) never hits either. Renaming
// only for in-process, same-JVM redefinition sidesteps the hazard without touching the
// instrumenters' real, name-preserving behavior, which an actual ClassFileTransformer must keep
// (you cannot rename a class being retransformed in place).
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

final class IsolatedDefinition {
  private IsolatedDefinition() {}

  private static final AtomicInteger COUNTER = new AtomicInteger();

  /** Renames {@code bytes}'s own class to a fresh, never-before-used internal name. */
  static RenamedClass rename(byte[] bytes, String originalInternalName) {
    String freshName = originalInternalName + "$Isolated" + COUNTER.getAndIncrement();
    ClassReader reader = new ClassReader(bytes);
    ClassWriter writer = new ClassWriter(0);
    reader.accept(
        new ClassRemapper(writer, new SimpleRemapper(originalInternalName, freshName)), 0);
    return new RenamedClass(freshName.replace('/', '.'), writer.toByteArray());
  }

  record RenamedClass(String binaryName, byte[] bytes) {}
}
