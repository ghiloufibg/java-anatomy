package dev.sevenrungs.compilertooling.profiler;

// The ClassFile API (JEP 484) implementation - the same ClassBuilder.transformMethod pattern
// TimingAgent (compiler-tooling) uses, but detecting allocation instructions rather than every
// method's start. Inserting the counting call *before* each allocation opcode needs no
// stack-shape bookkeeping: a static void call with a constant-string argument leaves the
// pre-existing operand stack untouched, which is what makes the same technique implementable
// identically in all three APIs here.
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

public final class ClassFileApiInstrumenter implements Instrumenter {

  private static final ClassDesc RECORDER =
      ClassDesc.of("dev.sevenrungs.compilertooling.profiler.AllocationRecorder");
  private static final MethodTypeDesc RECORD_DESC =
      MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String);

  @Override
  public byte[] instrument(String ownerInternalName, byte[] original) {
    // An explicit, resource-parsing ClassHierarchyResolver: it resolves supertypes by reading
    // .class bytes directly rather than relying on the ambient class-loading context - a more
    // deterministic default for a bytecode tool to ship, and one this implementation never
    // actually needed to fix a bug (see AgentProcessSupport's Javadoc for the real VerifyError
    // this phase's tests chased down; it turned out to be entirely a ByteBuddyInstrumenter issue,
    // unrelated to this class or to this resolver choice).
    ClassFile cf =
        ClassFile.of(
            ClassFile.StackMapsOption.GENERATE_STACK_MAPS,
            ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofResourceParsing(
                    ClassFileApiInstrumenter.class.getClassLoader())));
    ClassModel model = cf.parse(original);
    ClassTransform xform =
        (classBuilder, classElement) -> {
          if (classElement instanceof MethodModel mm
              && !mm.methodName().equalsString("<init>")
              && !mm.methodName().equalsString("<clinit>")) {
            String methodName = mm.methodName().stringValue();
            classBuilder.transformMethod(
                mm, MethodTransform.transformingCode(countingCode(ownerInternalName, methodName)));
          } else {
            classBuilder.with(classElement);
          }
        };
    return cf.transformClass(model, xform);
  }

  private static CodeTransform countingCode(String owner, String methodName) {
    return (cb, e) -> {
      String allocatedType = allocatedTypeOf(e);
      if (allocatedType != null) {
        cb.ldc(owner + "." + methodName + ":" + allocatedType)
            .invokestatic(RECORDER, "record", RECORD_DESC);
      }
      cb.with(e);
    };
  }

  private static String allocatedTypeOf(CodeElement e) {
    if (e instanceof NewObjectInstruction n) return n.className().asInternalName();
    if (e instanceof NewReferenceArrayInstruction n) {
      return n.componentType().asInternalName() + "[]";
    }
    if (e instanceof NewPrimitiveArrayInstruction n) return n.typeKind().name() + "[]";
    if (e instanceof NewMultiArrayInstruction n) return n.arrayType().asInternalName();
    return null;
  }
}
