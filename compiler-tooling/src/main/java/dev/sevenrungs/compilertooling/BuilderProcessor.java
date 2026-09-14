package dev.sevenrungs.compilertooling;

// An annotation processor (JSR 269, javax.annotation.processing) that generates a builder
// for any record marked @Builder. Runs inside javac; zero reflection at runtime.
// Registered for real discovery via META-INF/services/javax.annotation.processing.Processor,
// though this project's own test drives it by passing the instance directly to JavaCompiler's
// API instead of relying on that discovery (see BuilderProcessorTest).
import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

@SupportedAnnotationTypes("dev.sevenrungs.compilertooling.Builder")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class BuilderProcessor extends AbstractProcessor {
  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
    for (Element e : round.getElementsAnnotatedWith(Builder.class)) {
      if (e.getKind() != ElementKind.RECORD) {
        processingEnv
            .getMessager()
            .printMessage(Diagnostic.Kind.ERROR, "@Builder only on records", e);
        continue;
      }
      TypeElement rec = (TypeElement) e;
      var comps = rec.getRecordComponents();
      String pkg = processingEnv.getElementUtils().getPackageOf(rec).getQualifiedName().toString();
      String name = rec.getSimpleName() + "Builder";
      try (Writer w =
          processingEnv
              .getFiler()
              .createSourceFile(pkg.isEmpty() ? name : pkg + "." + name, rec)
              .openWriter()) {
        if (!pkg.isEmpty()) w.write("package " + pkg + ";\n\n");
        w.write("public final class " + name + " {\n");
        for (RecordComponentElement c : comps) {
          w.write("  private " + c.asType() + " " + c.getSimpleName() + ";\n");
        }
        for (RecordComponentElement c : comps) {
          w.write(
              "  public "
                  + name
                  + " "
                  + c.getSimpleName()
                  + "("
                  + c.asType()
                  + " v) { this."
                  + c.getSimpleName()
                  + " = v; return this; }\n");
        }
        w.write(
            "  public "
                + rec.getQualifiedName()
                + " build() { return new "
                + rec.getQualifiedName()
                + "("
                + comps.stream()
                    .map(c -> c.getSimpleName().toString())
                    .collect(Collectors.joining(", "))
                + "); }\n}\n");
      } catch (IOException ex) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, ex.toString(), e);
      }
    }
    return true; // claimed: no other processor sees @Builder
  }
}
// Expert notes: the `originating element` argument to createSourceFile is what makes
// incremental builds (Gradle) correct; and generated sources trigger another round,
// so idempotency is not optional (this processor only ever emits from @Builder-annotated
// elements themselves, never from its own generated output, so it naturally terminates).
