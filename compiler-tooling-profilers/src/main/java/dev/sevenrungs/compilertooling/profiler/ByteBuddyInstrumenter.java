package dev.sevenrungs.compilertooling.profiler;

// The Byte Buddy implementation. Its high-level Advice API only instruments method entry/exit,
// not arbitrary opcodes like NEW - counting every allocation site needs its lower-level
// AsmVisitorWrapper, which hands over a real ASM ClassVisitor. That ClassVisitor comes from
// net.bytebuddy.jar.asm, Byte Buddy's own *shaded, relocated copy* of ASM (so Byte Buddy never
// conflicts with whatever ASM version the host application already uses) - a real, worth-noting
// finding: this class cannot share a single line of visitor code with AsmInstrumenter even though
// both ultimately do the identical thing, because net.bytebuddy.jar.asm.ClassVisitor and
// org.objectweb.asm.ClassVisitor are unrelated types at the bytecode level.
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

public final class ByteBuddyInstrumenter implements Instrumenter {

  private static final String RECORDER =
      "dev/sevenrungs/compilertooling/profiler/AllocationRecorder";

  @Override
  public byte[] instrument(String ownerInternalName, byte[] original) {
    String typeName = ownerInternalName.replace('/', '.');
    ClassFileLocator locator =
        new ClassFileLocator.Compound(
            ClassFileLocator.Simple.of(typeName, original),
            ClassFileLocator.ForClassLoader.ofSystemLoader());
    TypeDescription typeDescription = TypePool.Default.of(locator).describe(typeName).resolve();

    return new ByteBuddy()
        .redefine(typeDescription, locator)
        .visit(new CountingVisitorWrapper(ownerInternalName))
        .make()
        .getBytes();
  }

  private static final class CountingVisitorWrapper extends AsmVisitorWrapper.AbstractBase {
    private final String owner;

    CountingVisitorWrapper(String owner) {
      this.owner = owner;
    }

    @Override
    public ClassVisitor wrap(
        TypeDescription instrumentedType,
        ClassVisitor classVisitor,
        Implementation.Context implementationContext,
        TypePool typePool,
        FieldList<FieldDescription.InDefinedShape> fields,
        MethodList<?> methods,
        int writerFlags,
        int readerFlags) {
      return new ClassVisitor(OpenedClassReader.ASM_API, classVisitor) {
        @Override
        public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
          MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
          if (name.equals("<init>") || name.equals("<clinit>")) return mv;
          return new CountingMethodVisitor(mv, owner, name);
        }
      };
    }

    // The actual bug this class shipped with, found once tests forked real child JVMs instead of
    // trusting an in-process defineClass(): AsmVisitorWrapper.AbstractBase's default mergeWriter
    // leaves the writer flags untouched, so Byte Buddy's underlying ClassWriter keeps reusing each
    // method's *original* max_stack. Every ldc+invokestatic this wrapper inserts before an
    // allocation opcode needs two extra stack slots the original bytecode never needed, so without
    // COMPUTE_MAXS the written class file understates its own max_stack and fails verification
    // with "Operand stack overflow" - deterministically, in a genuinely fresh JVM, independent of
    // JUnit or any in-process trick (unlike AsmInstrumenter and ClassFileApiInstrumenter, which
    // each request stack/frame recomputation explicitly and never hit this).
    @Override
    public int mergeWriter(int flags) {
      return flags | ClassWriter.COMPUTE_MAXS;
    }
  }

  private static final class CountingMethodVisitor extends MethodVisitor {
    private final String owner;
    private final String methodName;

    CountingMethodVisitor(MethodVisitor delegate, String owner, String methodName) {
      super(OpenedClassReader.ASM_API, delegate);
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
      emit(descriptor);
      super.visitMultiANewArrayInsn(descriptor, numDimensions);
    }

    private void emit(String allocatedType) {
      super.visitLdcInsn(owner + "." + methodName + ":" + allocatedType);
      super.visitMethodInsn(
          Opcodes.INVOKESTATIC, RECORDER, "record", "(Ljava/lang/String;)V", false);
    }

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
