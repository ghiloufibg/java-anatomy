package dev.sevenrungs.jvminternals.footprint;

import java.util.List;

/**
 * The JVM flag sets that change how many bytes each object costs, without changing a line of
 * application code.
 *
 * <p>What each one moves (HotSpot, 64-bit, JDK 25):
 *
 * <ul>
 *   <li>{@link #DEFAULT} - compressed oops (4-byte references) and compressed class pointers: an
 *       8-byte mark word plus a 4-byte klass pointer, a 12-byte header.
 *   <li>{@link #COMPACT_HEADERS} - JEP 519 (product in JDK 25): the klass pointer moves INTO the
 *       mark word, an 8-byte header. Every object saves 4 bytes, before alignment decides whether
 *       that becomes 0 or 8 bytes of real savings.
 *   <li>{@link #NO_COMPRESSED_CLASS_POINTERS} - a full 8-byte klass pointer, a 16-byte header. The
 *       flag is deprecated in 25 (the JVM prints a warning); it's here to show what compressed
 *       class pointers have been saving you all along.
 *   <li>{@link #NO_COMPRESSED_OOPS} - 8-byte references. This is what you get implicitly the moment
 *       {@code -Xmx} crosses ~32 GB, which is why a 31 GB heap can hold more than a 33 GB one.
 *   <li>{@link #ALIGNMENT_16} - {@code -XX:ObjectAlignmentInBytes=16} stretches compressed oops to
 *       64 GB heaps, paid for with padding on every object whose size isn't a multiple of 16.
 * </ul>
 */
public enum JvmMemoryConfig {
  DEFAULT("default (12-byte header, compressed oops)"),
  COMPACT_HEADERS("compact object headers", "-XX:+UseCompactObjectHeaders"),
  NO_COMPRESSED_CLASS_POINTERS("no compressed class pointers", "-XX:-UseCompressedClassPointers"),
  NO_COMPRESSED_OOPS("no compressed oops (heap > 32 GB)", "-XX:-UseCompressedOops"),
  ALIGNMENT_16("16-byte object alignment", "-XX:ObjectAlignmentInBytes=16");

  private final String description;
  private final List<String> flags;

  JvmMemoryConfig(String description, String... flags) {
    this.description = description;
    this.flags = List.of(flags);
  }

  public String description() {
    return description;
  }

  /** The extra JVM flags this configuration adds on top of the JVM's defaults. */
  public List<String> flags() {
    return flags;
  }
}
