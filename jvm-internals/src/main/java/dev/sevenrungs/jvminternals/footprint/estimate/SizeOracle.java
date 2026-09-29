package dev.sevenrungs.jvminternals.footprint.estimate;

import dev.sevenrungs.jvminternals.footprint.ChildJvm;
import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

/**
 * Asks a child JVM running under a {@link JvmMemoryConfig} how big each class really is there.
 *
 * <p>Why not arithmetic ("header minus 4 bytes, round to 8")? Because field layout is recomputed
 * per configuration: a {@code Long} is 24 bytes by default (12-byte header, 4 bytes of padding so
 * the {@code long} lands on 8) and 16 with compact headers (the {@code long} moves to offset 8 and
 * the padding disappears), where the arithmetic would predict no change at all. Only a JVM with the
 * flag set knows, so the oracle asks one, through JOL.
 */
public final class SizeOracle {
  private SizeOracle() {}

  /** Element layout of one array type: where elements start and how wide each one is. */
  public record ArrayLayout(int baseOffset, int indexScale) {}

  /**
   * Everything the estimator needs about one configuration.
   *
   * @param objectSizes instance size per resolvable non-array class
   * @param arrays layout per array class, keyed by descriptor name ({@code [B}, {@code [Lx;})
   * @param unresolved classes the child couldn't load by name - hidden classes, lambdas
   */
  public record Layouts(
      JvmMemoryConfig config,
      int alignment,
      Map<String, Long> objectSizes,
      Map<String, ArrayLayout> arrays,
      Set<String> unresolved) {}

  /** Measures every class in {@code classNames} under {@code config}, in one child JVM. */
  public static Layouts measure(
      JvmMemoryConfig config, Collection<String> classNames, String extraClasspath) {
    try {
      Path names = Files.createTempFile("classes-", ".txt");
      try {
        Files.write(names, classNames);
        String output = ChildJvm.run(config, extraClasspath, SizeOracle.class, names.toString());
        return parse(config, output);
      } finally {
        Files.deleteIfExists(names);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Layouts parse(JvmMemoryConfig config, String output) {
    int alignment = 0;
    Map<String, Long> objects = new HashMap<>();
    Map<String, ArrayLayout> arrays = new HashMap<>();
    Set<String> unresolved = new HashSet<>();
    for (String line : output.lines().toList()) {
      String[] f = line.split(" ");
      switch (f[0]) {
        case "ALIGNMENT" -> alignment = Integer.parseInt(f[1]);
        case "OBJECT" -> objects.put(f[1], Long.parseLong(f[2]));
        case "ARRAY" ->
            arrays.put(f[1], new ArrayLayout(Integer.parseInt(f[2]), Integer.parseInt(f[3])));
        case "UNRESOLVED" -> unresolved.add(f[1]);
        default -> {} // JVM warnings, JOL's banner
      }
    }
    if (alignment == 0) {
      throw new IllegalStateException("size oracle printed no ALIGNMENT line:\n" + output);
    }
    return new Layouts(config, alignment, objects, arrays, unresolved);
  }

  /** Child side: one line per class name read from the file named by {@code args[0]}. */
  public static void main(String[] args) throws IOException {
    var vm = VM.current();
    System.out.println("ALIGNMENT " + vm.objectAlignment());
    List<String> names = Files.readAllLines(Path.of(args[0]));
    ClassLoader loader = ClassLoader.getSystemClassLoader();
    for (String name : names) {
      if (name.isBlank()) {
        continue;
      }
      if (name.startsWith("[")) {
        String component = arrayComponent(name);
        System.out.printf(
            "ARRAY %s %d %d%n", name, vm.arrayBaseOffset(component), vm.arrayIndexScale(component));
        continue;
      }
      try {
        Class<?> c = Class.forName(name, false, loader);
        System.out.printf("OBJECT %s %d%n", name, ClassLayout.parseClass(c).instanceSize());
      } catch (Throwable e) { // ClassNotFound for hidden classes, LinkageError, JOL refusals
        System.out.println("UNRESOLVED " + name);
      }
    }
  }

  /**
   * The element type JOL expects: a primitive name, or any reference type for {@code [L}/{@code
   * [[}.
   */
  static String arrayComponent(String descriptor) {
    return switch (descriptor.charAt(1)) {
      case 'Z' -> "boolean";
      case 'B' -> "byte";
      case 'C' -> "char";
      case 'S' -> "short";
      case 'I' -> "int";
      case 'F' -> "float";
      case 'J' -> "long";
      case 'D' -> "double";
      default -> "java.lang.Object"; // [Lsome.Class; and nested arrays: element is a reference
    };
  }
}
