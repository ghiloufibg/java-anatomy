package dev.sevenrungs.compilertooling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link BuilderProcessor} through the real {@link JavaCompiler} API - the same mechanism
 * javac itself uses to run annotation processors - rather than relying on {@code META-INF/services}
 * discovery, which would need this module's own processor class already compiled before the same
 * module's compile step could use it on itself.
 */
class BuilderProcessorTest {

  @Test
  void generatesAWorkingBuilderForAnAnnotatedRecord(@TempDir Path tempDir) throws Exception {
    Path srcDir = Files.createDirectory(tempDir.resolve("src"));
    Path outDir = Files.createDirectory(tempDir.resolve("out"));

    Path pointSource = srcDir.resolve("Point.java");
    Files.writeString(
        pointSource,
        """
        import dev.sevenrungs.compilertooling.Builder;

        @Builder
        public record Point(int x, int y) {}
        """);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
      fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
      fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(outDir.toFile()));
      var units = fm.getJavaFileObjectsFromPaths(List.of(pointSource));

      JavaCompiler.CompilationTask task = compiler.getTask(null, fm, null, null, null, units);
      task.setProcessors(List.of(new BuilderProcessor()));

      assertTrue(task.call(), "compilation with BuilderProcessor should succeed");
    }

    assertTrue(Files.exists(outDir.resolve("PointBuilder.java")), "builder source generated");

    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {outDir.toUri().toURL()}, getClass().getClassLoader())) {
      Class<?> builderClass = Class.forName("PointBuilder", true, loader);
      Object builder = builderClass.getConstructor().newInstance();
      Method xSetter = builderClass.getMethod("x", int.class);
      Method ySetter = builderClass.getMethod("y", int.class);
      builder = xSetter.invoke(builder, 3);
      builder = ySetter.invoke(builder, 4);
      Object point = builderClass.getMethod("build").invoke(builder);

      assertEquals(3, point.getClass().getMethod("x").invoke(point));
      assertEquals(4, point.getClass().getMethod("y").invoke(point));
    }
  }

  @Test
  void rejectsANonRecordType(@TempDir Path tempDir) throws IOException {
    Path srcDir = Files.createDirectory(tempDir.resolve("src"));
    Path outDir = Files.createDirectory(tempDir.resolve("out"));

    Path badSource = srcDir.resolve("NotARecord.java");
    Files.writeString(
        badSource,
        """
        import dev.sevenrungs.compilertooling.Builder;

        @Builder
        public class NotARecord {}
        """);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
      fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
      var units = fm.getJavaFileObjectsFromPaths(List.of(badSource));

      JavaCompiler.CompilationTask task = compiler.getTask(null, fm, null, null, null, units);
      task.setProcessors(List.of(new BuilderProcessor()));

      assertTrue(!task.call(), "compilation should fail: @Builder only on records");
    }
  }
}
