package dev.sevenrungs.compilertooling.profiler.attribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import java.io.File;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Reading a target JVM's layout flags and classpath - no JVM attached. */
class LiveAttributionConfigTest {

  // the shape of `jcmd <pid> VM.flags -all` lines on JDK 25.0.1
  static String flags(String compact, String ccp, String oops, String alignment) {
    return """
        [Global flags]
             int ObjectAlignmentInBytes                   = %s                              {product lp64_product} {default}
            bool UseCompactObjectHeaders                  = %s                          {product lp64_product} {default}
            bool UseCompressedClassPointers               = %s                           {product lp64_product} {default}
            bool UseCompressedOops                        = %s                           {product lp64_product} {ergonomic}
        """
        .formatted(alignment, compact, ccp, oops);
  }

  @Test
  void eachModeledFlagSetIsRecognized() {
    assertEquals(
        JvmMemoryConfig.DEFAULT, LiveAttribution.detectConfig(flags("false", "true", "true", "8")));
    assertEquals(
        JvmMemoryConfig.COMPACT_HEADERS,
        LiveAttribution.detectConfig(flags("true", "true", "true", "8")));
    assertEquals(
        JvmMemoryConfig.NO_COMPRESSED_CLASS_POINTERS,
        LiveAttribution.detectConfig(flags("false", "false", "true", "8")));
    assertEquals(
        JvmMemoryConfig.NO_COMPRESSED_OOPS,
        LiveAttribution.detectConfig(flags("false", "true", "false", "8")));
    assertEquals(
        JvmMemoryConfig.ALIGNMENT_16,
        LiveAttribution.detectConfig(flags("false", "true", "true", "16")));
  }

  @Test
  void combinationsTheEnumDoesntModelAreRefusedNotApproximated() {
    assertThrows(
        IllegalStateException.class,
        () -> LiveAttribution.detectConfig(flags("true", "true", "true", "16")));
    assertThrows(
        IllegalStateException.class,
        () -> LiveAttribution.detectConfig(flags("false", "true", "true", "32")));
    assertThrows(IllegalStateException.class, () -> LiveAttribution.detectConfig("no flags here"));
  }

  @Test
  void relativeClasspathEntriesResolveAgainstTheTargetsWorkingDirectory() {
    var props = new Properties();
    props.setProperty("user.dir", "/srv/app");
    props.setProperty(
        "java.class.path", "lib/app.jar" + File.pathSeparator + "/opt/shared/dep.jar");
    assertEquals(
        "/srv/app/lib/app.jar" + File.pathSeparator + "/opt/shared/dep.jar",
        LiveAttribution.classpathOf(props));
  }
}
