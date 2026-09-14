# Phase 5 — Compiler and tooling craft: processors, agents, bytecode

The three tricks every framework is built from: generating code at compile time (an annotation
processor, JSR 269), rewriting classes at load time (a `java.lang.instrument` agent), and forging
code at run time (`invokedynamic` + hidden classes). All three are stable, unflagged JDK 25
features — no `--add-modules`, no preview flags, no `-Djava.lang.invoke...` needed for any of them.

## Medium — `BuilderProcessor`: a compile-time code generator

`@Builder` on a record makes `BuilderProcessor` emit a `<Record>Builder` class — a setter per
component plus `build()` — as real source, during `javac`'s own compilation, via
`Filer.createSourceFile`. No reflection at runtime; the generated class is just another `.java`
file the compiler processes in its next round.

Registered for real discovery via
`META-INF/services/javax.annotation.processing.Processor`, so any consumer just needs
`compiler-tooling` on its annotation-processor path. This module's own test instead drives
`javax.tools.JavaCompiler`'s API directly, passing `new BuilderProcessor()` straight to
`CompilationTask.setProcessors(...)` — no self-referential "this module discovers its own
processor while compiling itself" complication, and a cleaner way to assert on the exact generated
source and the resulting object's behavior in one test.

Run it for real:

```
@Builder
public record Point(int x, int y) {}
```

```
javac -processor dev.sevenrungs.compilertooling.BuilderProcessor Point.java
```

generates:

```java
public final class PointBuilder {
  private int x;
  private int y;
  public PointBuilder x(int v) { this.x = v; return this; }
  public PointBuilder y(int v) { this.y = v; return this; }
  public Point build() { return new Point(x, y); }
}
```

```
new PointBuilder().x(3).y(4).build()   →   Point[x=3, y=4]
```

— exactly what ran, captured above.

## Hard — `TimingAgent`: a `java.lang.instrument` agent using the ClassFile API

A `-javaagent` that inserts a `Recorder.enter(site)` call at the start of every non-constructor
method of every class whose name starts with a given prefix, using the ClassFile API (JEP 484,
final in JDK 24) — the same library `javac` and `jlink` themselves use, no ASM or Byte Buddy
involved.

### The artifact's own snippet does not compile on this JDK

The artifact this class is adapted from reaches the enclosing method's name from inside a
`CodeTransform` via `cb.original().get().parent().get()`. That method does not exist anywhere in
the finalized `java.lang.classfile` API — confirmed by reflecting over the real
`java.lang.classfile.CodeBuilder` class on this JDK; JEP 484 finalized in JDK 24 after the artifact
was written, and the API shifted under it.

The real way to reach the enclosing method's name while transforming its body: build the
`ClassTransform` as a lambda that pattern-matches each `MethodModel` class element directly (its
name is right there, in the outer lambda's scope — no reflection back through the builder needed),
then hand that method to `ClassBuilder.transformMethod(MethodModel, MethodTransform)` alongside a
`MethodTransform` built from a `CodeTransform`:

```java
ClassTransform addEntryTiming = (classBuilder, classElement) -> {
  if (classElement instanceof MethodModel mm
      && !mm.methodName().equalsString("<init>")
      && !mm.methodName().equalsString("<clinit>")) {
    String site = className + "." + mm.methodName().stringValue();
    classBuilder.transformMethod(mm, MethodTransform.transformingCode(enterCall(site)));
  } else {
    classBuilder.with(classElement);
  }
};
```

Verified end to end as a real `-javaagent` before writing the final module code.

### Run it for real

Packaged with `Premain-Class: dev.sevenrungs.compilertooling.TimingAgent` and
`Can-Retransform-Classes: true` manifest entries (wired into this module's `maven-jar-plugin`
config), so the module's own jar works directly:

```
java -javaagent:compiler-tooling-1.0.0.jar=dev.sevenrungs.compilertooling.Fixture -cp . Fixture
```

Real captured output, against a two-method fixture (`main` calling `add(int,int)`):

```
ENTER dev/sevenrungs/compilertooling/Fixture.main
ENTER dev/sevenrungs/compilertooling/Fixture.add
result=5
```

`className` there is the JVM's own internal (slash-separated) form, exactly as
`ClassFileTransformer.transform` hands it in — matching the artifact's own example
(`"com/acme/orders/Foo.bar"`), which `TimingAgentTest` asserts on directly.

Building this agent's jar by hand needs every `.class` file the compiler emitted for
`TimingAgent`, not just the two you'd name from reading the source: `enterCall`'s
`new CodeTransform() {...}` is an anonymous class, so `TimingAgent$Transformer$1.class` exists too
and is silently missing if you hand-pick file names instead of walking the package directory —
discovered by tripping over it while spot-checking this exact command, which is why every jar this
project builds programmatically (`TimingAgentTest`, `AgentProcessSupport`) walks the whole compiled
package directory rather than listing files.

### What's deliberately left out

Exit timing needs every `return` instrumented *and* an exception handler wrapping the whole method
body (a try/finally at the bytecode level), plus care around `invokespecial <init>` ordering inside
constructors — genuinely harder than entry timing, and left as the natural next exercise, same as
the artifact frames it. Verify any such addition with `javap -v -p Foo.class`: the StackMapTable
frames (JVMS §4.7.4) the ClassFile API regenerates for you should still be present and correct.

## Guru — `IndyForge`: forging a class with `invokedynamic` and hidden classes

Builds a class file by hand (via the ClassFile API) containing one method whose body is a single
`invokedynamic` instruction bound to *our own* bootstrap method, defines it as a hidden class (JEP
371) via `MethodHandles.Lookup.defineHiddenClass`, and watches the JVM link the call site exactly
once no matter how many times it's invoked (JVMS §5.4.3.6, §6.5) — this is the mechanism underneath
lambdas, string concatenation (JEP 280), and records' generated `toString`/`equals`/`hashCode`.

### A real hidden-class packaging bug, found while moving this into a real package

`defineHiddenClass` requires the hidden class to be in the **same package** as the `Lookup`'s own
defining class. The artifact names the forged class `Forged` in the unnamed package — fine there,
because the artifact's own classes are also unpackaged. Once `IndyForge` moved into
`dev.sevenrungs.compilertooling`, keeping the forged class named `Forged` (unnamed package) threw:

```
IllegalArgumentException: Forged not in same package as lookup class
```

Fixed by naming the forged class `dev.sevenrungs.compilertooling.Forged` — the same package as
`IndyForge` itself.

### Run it for real

```java
MethodHandle h1 = IndyForge.forgeHello();
MethodHandle h2 = IndyForge.forgeHello();
String a = (String) h1.invokeExact("Ada");
String b = (String) h2.invokeExact("Alan");
```

Real captured output:

```
linking call site 'greet' as (String)String from class dev.sevenrungs.compilertooling.Forged/0x0000000067041000
linking call site 'greet' as (String)String from class dev.sevenrungs.compilertooling.Forged/0x0000000067041400
Hello, Ada!
Hello, Alan!
linkCount=2
```

The bootstrap prints once **per forged class** — `linkCount` is 2 here because `forgeHello()` was
called twice, forging (and hiding, and linking) two separate classes, each with its own call site
linked exactly once. `IndyForgeTest` asserts the same property the other way: repeated
`invokeExact` calls through a *single* forged handle only ever link once.

One real gotcha along the way: `invokeExact` is signature-polymorphic and infers its expected type
from syntactic call context. Used as a bare statement with no cast or assignment, it infers a
`void` return — mismatching the real `(String)String` handle and throwing
`WrongMethodTypeException`. The fix is exactly what's shown above: assign or cast the result.

## Running the tests

```
mvn -q -pl compiler-tooling -am install
```

`BuilderProcessorTest` drives the `JavaCompiler` API directly (no agent, no subprocess).
`TimingAgentTest` and `IndyForgeTest` fork a real child JVM (via `ProcessBuilder`) to prove the
real `-javaagent` path and the real hidden-class-linking behavior end to end, not just an
in-process approximation — the same pattern this project's earlier phases use for anything that
depends on genuine JVM startup/agent-attach/class-loading behavior.
