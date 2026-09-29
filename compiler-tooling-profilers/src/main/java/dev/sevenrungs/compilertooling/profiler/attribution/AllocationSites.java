package dev.sevenrungs.compilertooling.profiler.attribution;

import dev.sevenrungs.compilertooling.profiler.AllocationRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The allocation sites {@code AllocationAgent} printed at shutdown ({@link
 * AllocationRecorder#report()}: one {@code owner.method:type = count} line per site), with each
 * allocated type translated to the name a class histogram uses for it.
 */
public record AllocationSites(List<Site> sites) {

  /**
   * One site: {@code count} allocations of {@code allocatedClass} (histogram naming, e.g. {@code
   * [B}) made by {@code method} of {@code owner} (dotted binary name).
   */
  public record Site(String owner, String method, String allocatedClass, long count) {
    public String location() {
      return owner + "." + method;
    }
  }

  //   dev/sevenrungs/app/Orders.load:java/util/HashMap = 250000
  private static final Pattern LINE = Pattern.compile("^(\\S+)\\.([^.:\\s]+):(\\S+) = (\\d+)$");

  public AllocationSites {
    sites = List.copyOf(sites);
  }

  /** Parses the agent's report out of {@code output}, ignoring every line that isn't one. */
  public static AllocationSites parse(String output) {
    List<Site> sites = new ArrayList<>();
    for (String line : output.lines().toList()) {
      var m = LINE.matcher(line.trim());
      if (m.matches()) {
        sites.add(
            new Site(
                m.group(1).replace('/', '.'),
                m.group(2),
                histogramName(m.group(3)),
                Long.parseLong(m.group(4))));
      }
    }
    return new AllocationSites(sites);
  }

  /** Sites grouped by the histogram class they allocate. */
  public Map<String, List<Site>> byAllocatedClass() {
    return sites.stream().collect(Collectors.groupingBy(Site::allocatedClass));
  }

  /**
   * The agent labels allocations with bytecode-level names - {@code java/util/HashMap} for {@code
   * new}, {@code java/lang/Object[]} for {@code anewarray}, {@code byte[]} for {@code newarray},
   * and a full descriptor such as {@code [[I} for {@code multianewarray}. A class histogram uses
   * binary names for classes and descriptors for every array; this maps the former to the latter.
   */
  static String histogramName(String agentType) {
    if (!agentType.endsWith("[]")) {
      return agentType.replace('/', '.'); // a class, or multianewarray's ready-made descriptor
    }
    String element = agentType.substring(0, agentType.length() - 2);
    return "["
        + switch (element) {
          case "boolean" -> "Z";
          case "byte" -> "B";
          case "char" -> "C";
          case "short" -> "S";
          case "int" -> "I";
          case "float" -> "F";
          case "long" -> "J";
          case "double" -> "D";
          default ->
              element.startsWith("[")
                  ? element.replace('/', '.') // anewarray of arrays, e.g. new int[3][]: [I[]
                  : "L" + element.replace('/', '.') + ";";
        };
  }
}
