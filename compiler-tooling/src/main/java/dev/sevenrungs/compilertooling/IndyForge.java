package dev.sevenrungs.compilertooling;

// Emit a class by hand that uses invokedynamic with OUR bootstrap method, define it as a hidden
// class, and watch the JVM link the call site exactly once (JVMS §5.4.3.6, §6.5).
// This is the mechanism under lambdas, string concat (JEP 280), records' toString, and
// every "dynamic language on the JVM". After this, java.lang.invoke stops being magic.
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_String;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicInteger;

public final class IndyForge {

  static final AtomicInteger linkCount = new AtomicInteger();

  // Bootstrap: called ONCE per call site, at first execution, with the static arguments.
  public static CallSite bootstrap(
      MethodHandles.Lookup caller, String name, MethodType type, String greeting) throws Throwable {
    linkCount.incrementAndGet();
    System.out.println(
        "linking call site '" + name + "' as " + type + " from " + caller.lookupClass());
    MethodHandle target =
        MethodHandles.lookup()
            .findStatic(
                IndyForge.class,
                "greet",
                MethodType.methodType(String.class, String.class, String.class));
    return new ConstantCallSite(
        MethodHandles.insertArguments(target, 0, greeting)); // curry the greeting
    // Use a MutableCallSite here and you have hot-swappable dispatch that C2 will still inline
    // until you call setTarget(), which deoptimizes the dependent code (JVMS §5.4.3.6 note).
  }

  static String greet(String greeting, String who) {
    return greeting + ", " + who + "!";
  }

  /** Forges the {@code Forged.hello(String)} class and returns a handle to it. */
  static MethodHandle forgeHello() throws Throwable {
    // defineHiddenClass requires the hidden class to be in the same package as the lookup class
    // that defines it (IndyForge itself here) - discovered the hard way: naming the forged class
    // "Forged" in the unnamed package (as in the artifact, which is itself unpackaged) throws
    // "Forged not in same package as lookup class" once IndyForge lives in a real package.
    ClassDesc self = ClassDesc.of("dev.sevenrungs.compilertooling.IndyForge");
    DirectMethodHandleDesc bsm =
        MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC,
            self,
            "bootstrap",
            MethodTypeDesc.of(
                ClassDesc.of("java.lang.invoke.CallSite"),
                CD_MethodHandles_Lookup,
                CD_String,
                CD_MethodType,
                CD_String));

    byte[] bytes =
        ClassFile.of()
            .build(
                ClassDesc.of("dev.sevenrungs.compilertooling.Forged"),
                cb ->
                    cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
                        .withMethodBody(
                            "hello",
                            MethodTypeDesc.of(CD_String, CD_String),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                            code ->
                                code.aload(0)
                                    .invokedynamic(
                                        DynamicCallSiteDesc.of(
                                            bsm,
                                            "greet",
                                            MethodTypeDesc.of(CD_String, CD_String),
                                            "Hello"))
                                    .areturn()));

    // Hidden class (JEP 371): unnamed for the loader, unloadable with its nest, exactly what
    // lambdas use.
    MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(bytes, true);
    return lookup.findStatic(
        lookup.lookupClass(), "hello", MethodType.methodType(String.class, String.class));
  }

  public static void main(String[] args) throws Throwable {
    MethodHandle hello = forgeHello();
    System.out.println((String) hello.invokeExact("Duke")); // bootstrap prints once...
    System.out.println((String) hello.invokeExact("Loom")); // ...and not again: site is linked
  }
}
// Go further: -Djava.lang.invoke.MethodHandle.DUMP_CLASS_FILES=true shows the LambdaForms the
// JDK spins for your handles; -XX:+PrintCompilation shows the site inlining into the caller.
