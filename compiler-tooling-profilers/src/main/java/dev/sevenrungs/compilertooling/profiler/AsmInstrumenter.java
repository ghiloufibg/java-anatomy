package dev.sevenrungs.compilertooling.profiler;

// The ASM implementation. Same technique as ClassFileApiInstrumenter (a counting call inserted
// immediately before each allocation opcode, needing no stack-shape bookkeeping), but ASM's
// visitor model reaches allocation instructions directly - no need to distinguish "which class
// element is a method" first, the ClassVisitor/MethodVisitor split already hands you a
// per-method stream of individual opcodes.
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class AsmInstrumenter implements Instrumenter {

  private static final String RECORDER =
      "dev/sevenrungs/compilertooling/profiler/AllocationRecorder";

  @Override
  public byte[] instrument(String ownerInternalName, byte[] original) {
    ClassReader reader = new ClassReader(original);
    ClassWriter writer =
        new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    reader.accept(new CountingClassVisitor(writer, ownerInternalName), 0);
    return writer.toByteArray();
  }

  private static final class CountingClassVisitor extends ClassVisitor {
    private final String owner;

    CountingClassVisitor(ClassVisitor delegate, String owner) {
      super(Opcodes.ASM9, delegate);
      this.owner = owner;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      if (name.equals("<init>") || name.equals("<clinit>")) return mv;
      return new CountingMethodVisitor(mv, owner, name);
    }
  }

  private static final class CountingMethodVisitor extends MethodVisitor {
    private final String owner;
    private final String methodName;

    CountingMethodVisitor(MethodVisitor delegate, String owner, String methodName) {
      super(Opcodes.ASM9, delegate);
      this.owner = owner;
      this.methodName = methodName;
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
      if (opcode == Opcodes.NEW) {
        emit(type);
      } else if (opcode == Opcodes.ANEWARRAY) {
        emit(type + "[]");
      }
      super.visitTypeInsn(opcode, type);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
      if (opcode == Opcodes.NEWARRAY) {
        emit(primitiveArrayTypeName(operand) + "[]");
      }
      super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
      emit(descriptor); // already the full array descriptor, e.g. "[[I" - no extra "[]" needed
      super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }

    private void emit(String allocatedType) {
      super.visitLdcInsn(owner + "." + methodName + ":" + allocatedType);
      super.visitMethodInsn(
          Opcodes.INVOKESTATIC, RECORDER, "record", "(Ljava/lang/String;)V", false);
    }

    // Matches java.lang.classfile.TypeKind's enum names, so ClassFileApiInstrumenter and this
    // implementation report identical site strings for the same primitive array allocation.
    private static String primitiveArrayTypeName(int atype) {
      return switch (atype) {
        case Opcodes.T_BOOLEAN -> "BOOLEAN";
        case Opcodes.T_CHAR -> "CHAR";
        case Opcodes.T_FLOAT -> "FLOAT";
        case Opcodes.T_DOUBLE -> "DOUBLE";
        case Opcodes.T_BYTE -> "BYTE";
        case Opcodes.T_SHORT -> "SHORT";
        case Opcodes.T_INT -> "INT";
        case Opcodes.T_LONG -> "LONG";
        default -> throw new IllegalArgumentException("unknown newarray atype: " + atype);
      };
    }
  }
}
