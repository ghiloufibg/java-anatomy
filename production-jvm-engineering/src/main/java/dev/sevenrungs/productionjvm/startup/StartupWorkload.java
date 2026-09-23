package dev.sevenrungs.productionjvm.startup;

import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

/**
 * A typical service's boot sequence in miniature: parse XML config, compile a regex, format dates,
 * hash, spin up virtual threads, build an HTTP client, touch logging. None of it is expensive to
 * <em>run</em>; what's expensive is that it loads ~2,500 classes, and every one of them has to be
 * found, parsed, verified and linked - unless a CDS archive or AOT cache already did that.
 *
 * <p>Prints one {@code STARTUP key=value ...} line for {@link StartupComparison} to parse. It is
 * also the training run: whatever it loads is what {@code -XX:ArchiveClassesAtExit} and {@code
 * -XX:AOTCacheOutput} archive.
 */
public final class StartupWorkload {
  private StartupWorkload() {}

  public static void main(String[] args) throws Exception {
    long start = System.nanoTime();

    var xml =
        "<config><service name='orders' port='8080'/><service name='billing' port='8081'/></config>";
    var config =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new InputSource(new StringReader(xml)));
    int services = config.getElementsByTagName("service").getLength();

    Map<String, Long> words =
        Pattern.compile("\\W+")
            .splitAsStream("the quick brown fox jumps over the lazy dog")
            .collect(Collectors.groupingBy(w -> w, TreeMap::new, Collectors.counting()));
    String date = LocalDate.of(2026, 9, 23).format(DateTimeFormatter.ofPattern("EEEE d MMMM uuuu"));
    String digest =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(xml.getBytes(StandardCharsets.UTF_8)));

    var buckets = new ConcurrentHashMap<Integer, Integer>();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      IntStream.range(0, 100)
          .forEach(i -> executor.submit(() -> buckets.merge(i % 10, 1, Integer::sum)));
    }
    try (var client = HttpClient.newHttpClient()) {
      URI.create("http://localhost:8080/orders").resolve(client.version().name());
    }
    Logger.getLogger("startup").fine("ready");

    long mainMillis = (System.nanoTime() - start) / 1_000_000;
    if (services != 2 || words.size() != 8 || date.isEmpty() || digest.length() != 64) {
      throw new IllegalStateException("workload computed the wrong answer");
    }
    System.out.printf(
        "STARTUP mainMillis=%d loadedClasses=%d metaspaceUsedKb=%d classSpaceUsedKb=%d%n",
        mainMillis,
        ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
        poolUsedKb("Metaspace"),
        poolUsedKb("Compressed Class Space"));
  }

  private static long poolUsedKb(String name) {
    return ManagementFactory.getMemoryPoolMXBeans().stream()
        .filter(p -> p.getName().equals(name))
        .map(MemoryPoolMXBean::getUsage)
        .mapToLong(u -> u.getUsed() / 1024)
        .sum();
  }
}
