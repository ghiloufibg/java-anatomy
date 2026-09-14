package dev.sevenrungs.nativeinterop.bench;

// The JNI baseline the benchmark compares FFM against: a hand-written native method around
// zlib's compress2(), pinning the input array with GetPrimitiveArrayCritical instead of copying
// it - see src/main/c/jni_zlib.c and this module's README for why that, not
// Linker.Option.critical, is the real zero-copy heap-array primitive on this JDK.
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class JniZlib {

  static {
    loadNativeLibrary();
  }

  private JniZlib() {}

  /** Pins the input array with GetPrimitiveArrayCritical - the zero-copy path. */
  static native byte[] compress(byte[] input, int level);

  /**
   * Uses GetByteArrayElements, which the JVM is free to satisfy with a copy - the baseline path.
   */
  static native byte[] compressCopying(byte[] input, int level);

  /**
   * Loads {@code libjniz.so} from the classpath. Bound straight into {@code target/classes/native}
   * by this module's build (see pom.xml), so it resolves as a plain file during {@code mvn
   * test}/{@code exec:exec}; once bundled inside {@code benchmarks.jar} the resource URL comes back
   * with a {@code jar:} scheme instead, which {@code System.load} cannot open directly, so that
   * case is extracted to a real temp file first.
   */
  private static void loadNativeLibrary() {
    URL resource = JniZlib.class.getResource("/native/libjniz.so");
    if (resource == null) {
      throw new IllegalStateException("libjniz.so not found on the classpath under /native");
    }
    try {
      if ("file".equals(resource.getProtocol())) {
        System.load(new File(resource.toURI()).getAbsolutePath());
        return;
      }
      Path tempLib = Files.createTempFile("libjniz", ".so");
      tempLib.toFile().deleteOnExit();
      try (InputStream in = resource.openStream()) {
        Files.copy(in, tempLib, StandardCopyOption.REPLACE_EXISTING);
      }
      System.load(tempLib.toAbsolutePath().toString());
    } catch (IOException | URISyntaxException e) {
      throw new IllegalStateException("failed to load libjniz.so", e);
    }
  }
}
