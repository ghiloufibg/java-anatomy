package dev.sevenrungs.jvminternals.footprint.estimate;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.management.JMException;
import javax.management.ObjectName;

/**
 * A live-object class histogram: every class on the heap after a full GC, with its instance count
 * and total bytes - the same table {@code jcmd <pid> GC.class_histogram} prints.
 *
 * <p>This is the estimator's source of truth because it sees <em>everything</em> retained,
 * JDK-internal objects included ({@code HashMap$Node}, the {@code byte[]} behind every {@code
 * String}), which a bytecode-rewriting agent restricted to application classes cannot.
 */
public record LiveHistogram(List<Entry> entries) {

  /** One histogram row. Array classes keep their JVM descriptor name, e.g. {@code [B}. */
  public record Entry(String className, long instances, long bytes) {
    public boolean isArray() {
      return className.startsWith("[");
    }
  }

  //   1:        100256        2406144  java.lang.Long (java.base@25.0.1)
  private static final Pattern ROW =
      Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)(?:\\s+\\(.*\\))?\\s*$");

  public LiveHistogram {
    entries = List.copyOf(entries);
  }

  /** Parses {@code GC.class_histogram} output, skipping its header and {@code Total} lines. */
  public static LiveHistogram parse(String text) {
    List<Entry> entries = new ArrayList<>();
    for (String line : text.lines().toList()) {
      var m = ROW.matcher(line);
      if (m.matches()) {
        entries.add(new Entry(m.group(3), Long.parseLong(m.group(1)), Long.parseLong(m.group(2))));
      }
    }
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("no histogram rows found");
    }
    return new LiveHistogram(entries);
  }

  /**
   * Captures this JVM's own live histogram through the {@code DiagnosticCommand} MBean - the same
   * command {@code jcmd} sends, so it forces a full GC first and counts only reachable objects.
   */
  public static LiveHistogram ofThisJvm() {
    try {
      String text =
          (String)
              ManagementFactory.getPlatformMBeanServer()
                  .invoke(
                      new ObjectName("com.sun.management:type=DiagnosticCommand"),
                      "gcClassHistogram",
                      new Object[] {new String[0]},
                      new String[] {String[].class.getName()});
      return parse(text);
    } catch (JMException e) {
      throw new IllegalStateException("GC.class_histogram is unavailable on this JVM", e);
    }
  }

  /**
   * What this histogram holds beyond {@code baseline}, class by class; classes that didn't grow are
   * dropped. Subtracting an idle JVM's histogram isolates the application's own live set from the
   * JDK's startup objects, which differ between configurations for reasons unrelated to layout (a
   * flag set that doesn't match the JDK's default CDS archive boots without it).
   */
  public LiveHistogram minus(LiveHistogram baseline) {
    Map<String, Entry> base = new HashMap<>();
    baseline.entries().forEach(e -> base.put(e.className(), e));
    List<Entry> grown = new ArrayList<>();
    for (Entry e : entries) {
      Entry b = base.getOrDefault(e.className(), new Entry(e.className(), 0, 0));
      if (e.instances() > b.instances() && e.bytes() > b.bytes()) {
        grown.add(new Entry(e.className(), e.instances() - b.instances(), e.bytes() - b.bytes()));
      }
    }
    return new LiveHistogram(grown);
  }

  /** Renders the rows back in {@code GC.class_histogram}'s format, so {@link #parse} reads them. */
  public String toText() {
    var rows = new StringBuilder();
    int i = 1;
    for (Entry e : entries) {
      rows.append("%5d: %13d %14d  %s%n".formatted(i++, e.instances(), e.bytes(), e.className()));
    }
    return rows.toString();
  }

  public long totalBytes() {
    return entries.stream().mapToLong(Entry::bytes).sum();
  }
}
