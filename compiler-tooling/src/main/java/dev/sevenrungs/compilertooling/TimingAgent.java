package dev.sevenrungs.compilertooling;

// A java.lang.instrument agent that rewrites bytecode with the ClassFile API (JEP 484, final in
// 24). No ASM, no Byte Buddy: the JDK's own library, the same one javac and jlink use.
// MANIFEST: Premain-Class: dev.sevenrungs.compilertooling.TimingAgent, Can-Retransform-Classes:
// true (wired into compiler-tooling/pom.xml's maven-jar-plugin config).
// Run:  java -javaagent:compiler-tooling-1.0.0.jar=com.acme.orders. -jar app.jar
//
// The artifact this class is adapted from calls cb.original().get().parent().get() to find the
// enclosing method's name from inside a CodeTransform - that method does not exist anywhere in
// the finalized JEP 484 API (confirmed by reflecting over java.lang.classfile.CodeBuilder on this
// JDK; the artifact predates finalization). The real way to reach the enclosing method while
// transforming its body: build the ClassTransform as a lambda that pattern-matches each
// MethodModel class element directly, then hand it to ClassBuilder.transformMethod alongside a
// MethodTransform built from the CodeTransform - the method's own name is captured in that outer
// lambda's scope, no reflection back through the builder needed.
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public final class TimingAgent {
  private TimingAgent() {}

  public static void premain(String prefix, Instrumentation inst) {
    inst.addTransformer(new Transformer(prefix), true);
  }

  /** Package-visible so the test can drive the same transform without a real agent attach. */
  static final class Transformer implements ClassFileTransformer {
    private final String prefix;

    Transformer(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public byte[] transform(
        Module module,
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain domain,
        byte[] classfileBuffer) {
      if (className == null || !className.replace('/', '.').startsWith(prefix)) {
        return null; // null = untouched
      }
      ClassFile cf = ClassFile.of();
      ClassModel model = cf.parse(classfileBuffer);
      ClassTransform addEntryTiming =
          (classBuilder, classElement) -> {
            if (classElement instanceof MethodModel mm
                && !mm.methodName().equalsString("<init>")
                && !mm.methodName().equalsString("<clinit>")) {
              String site = className + "." + mm.methodName().stringValue();
              classBuilder.transformMethod(mm, MethodTransform.transformingCode(enterCall(site)));
            } else {
              classBuilder.with(classElement);
            }
          };
      return cf.transformClass(model, addEntryTiming);
    }

    // Emit at entry:  Recorder.enter("com/acme/orders/Foo.bar")
    // Copies every original instruction after that; the library recomputes StackMapTable
    // frames (JVMS §4.7.4) and max stack for us.
    private static CodeTransform enterCall(String site) {
      return new CodeTransform() {
        @Override
        public void atStart(CodeBuilder cb) {
          cb.ldc(site)
              .invokestatic(
                  ClassDesc.of("dev.sevenrungs.compilertooling.Recorder"),
                  "enter",
                  MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
        }

        @Override
        public void accept(CodeBuilder cb, CodeElement e) {
          cb.with(e);
        }
      };
    }
  }
}
// Where this gets hard for real: exit timing needs every `return` AND an exception handler
// wrapping the whole body (try/finally at the bytecode level), plus care around
// `invokespecial <init>` ordering in constructors. That is deliberately left as the next
// exercise, same as the artifact frames it - see compiler-tooling/README.md.
// Verify output with: javap -v -p Foo.class (StackMapTable frames present and correct).
