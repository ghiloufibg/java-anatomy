package dev.sevenrungs.productionjvm;

// The exercise's service: a minimal HTTP endpoint with a realistic allocation profile (string
// building, boxed collections, a synchronized pricing cache) and an occasional deliberately
// contended lock, so there is something for every FlightDeck signal to actually observe. Runs
// standalone, or confined to a cgroup via ContainerCgroup - the point of the exercise is running
// this same class under G1, generational ZGC and Shenandoah in turn and comparing the numbers.
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;

public final class OrderHttpService {

  private static final Object PRICING_LOCK = new Object();
  private static final Map<String, Long> PRICING_CACHE = new HashMap<>();

  /** args: [durationSeconds] [port (0 = ephemeral)] */
  public static void main(String[] args) throws Exception {
    int durationSeconds = args.length > 0 ? Integer.parseInt(args[0]) : 5;
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 0;

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/order", OrderHttpService::handle);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    System.out.println("listening on " + server.getAddress().getPort());

    try (var deck = new FlightDeck()) {
      deck.startAsync();
      Thread.sleep(durationSeconds * 1000L);
      deck.stopAndDrain();
      FlightDeck.Metrics m = deck.snapshot();
      System.out.printf(
          "FLIGHTDECK gcPauseCount=%d p99GcPauseUpperBoundMicros=%d allocRateBytesPerSecond=%.1f"
              + " monitorContentionMillis=%d virtualThreadPinnedEvents=%d%n",
          m.gcPauseCount(),
          m.p99GcPauseUpperBoundMicros(),
          m.allocRateBytesPerSecond(),
          m.monitorContentionMillis(),
          m.virtualThreadPinnedEvents());
    } finally {
      server.stop(0);
    }
  }

  private static void handle(com.sun.net.httpserver.HttpExchange exchange)
      throws java.io.IOException {
    String orderId = "o-" + new Random().nextInt(1_000_000);
    long total = price(orderId, 1 + new Random().nextInt(20));
    // A realistic, allocation-heavy response body: string concatenation, not a StringBuilder,
    // is the point - this is what makes the workload worth pointing a profiler at.
    String body = "{\"orderId\":\"" + orderId + "\",\"totalCents\":" + total + "}";
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  /**
   * A deliberately contended cache lookup/populate - the source of jdk.JavaMonitorEnter events
   * under concurrent virtual-thread request handling.
   */
  private static long price(String orderId, int lines) {
    synchronized (PRICING_LOCK) {
      return PRICING_CACHE.computeIfAbsent(orderId + ":" + lines, k -> lines * 1_999L);
    }
  }
}
