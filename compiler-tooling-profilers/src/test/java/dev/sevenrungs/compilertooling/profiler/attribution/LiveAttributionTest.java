package dev.sevenrungs.compilertooling.profiler.attribution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.tools.attach.VirtualMachine;
import dev.sevenrungs.compilertooling.profiler.AgentJar;
import dev.sevenrungs.compilertooling.profiler.attribution.LiveAttribution.LiveCapture;
import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import dev.sevenrungs.jvminternals.footprint.OrderGraph;
import dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/**
 * Attaches to a {@link ChurningOrders} JVM that is already running - no {@code -javaagent} on its
 * command line - watches it for a few seconds, and checks what a late attach can and can't know (~2
 * target JVMs plus the size oracle's, ~20 s).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveAttributionTest {
  static final int ORDERS = 25_000;
  static final String PREFIX = OrderGraph.class.getName();
  static final String ORDER_METHOD = PREFIX + ".order";

  private Process target;
  private Path targetOutput;
  private LiveCapture live;
  private Map<JvmMemoryConfig, AllocationAttribution> attributions;
  private Map<JvmMemoryConfig, FootprintEstimate> estimates;

  @BeforeAll
  @Timeout(300)
  void attachToARunningJvm() throws Exception {
    targetOutput = Files.createTempFile("churning-", ".out");
    target =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx1g",
                "-XX:+UseSerialGC",
                // declares the intent the JDK now asks for (JEP 451) instead of warning per attach
                "-XX:+EnableDynamicAgentLoading",
                "-cp",
                System.getProperty("java.class.path"),
                ChurningOrders.class.getName(),
                String.valueOf(ORDERS))
            .redirectErrorStream(true)
            .redirectOutput(targetOutput.toFile())
            .start();
    awaitReady();

    live = LiveAttribution.capture(target.pid(), PREFIX, Duration.ofSeconds(3));
    attributions =
        SavingsAttributionReport.attributeAll(live.capture(), live.source(), live.classpath());
    estimates =
        dev.sevenrungs.jvminternals.footprint.estimate.FootprintEstimator.estimateAll(
            live.capture().liveSet(), live.capture().arrays(), live.source(), live.classpath());
  }

  @AfterAll
  void stopTarget() throws Exception {
    if (target != null) {
      target.getOutputStream().close(); // ChurningOrders exits when its stdin closes
      if (!target.waitFor(30, TimeUnit.SECONDS)) {
        target.destroyForcibly();
      }
    }
  }

  @Test
  void theTargetsLayoutFlagsAreDetected() {
    assertEquals(JvmMemoryConfig.DEFAULT, live.source());
    assertTrue(live.classpath().contains("classes"), live.classpath());
  }

  @Test
  void theAgentCountedOnlyTheWindowsAllocationsInTheOrdersRatio() {
    var byClass = live.capture().sites().byAllocatedClass();
    long orders = count(byClass, OrderGraph.Order.class.getName());
    long lineItems = count(byClass, OrderGraph.LineItem.class.getName());
    assertAll(
        () -> assertTrue(orders > ORDERS, "a 3 s window turns the live set over many times"),
        // counted per call to order(): 3 LineItems and 1 HashMap per Order, exactly - unless the
        // report caught order() mid-call, which can skew the counts by one call at most
        () -> assertTrue(Math.abs(lineItems - 3 * orders) <= 3, lineItems + " vs 3 x " + orders),
        () ->
            assertTrue(
                Math.abs(count(byClass, "java.util.HashMap") - orders) <= 1, "one HashMap each"));
  }

  @Test
  void classesFullyTurnedOverDuringTheWindowAreAttributedExactly() {
    // every live LineItem was allocated during the window, so all of its change is order()'s
    FootprintEstimate estimate = estimates.get(JvmMemoryConfig.COMPACT_HEADERS);
    AllocationAttribution compact = attributions.get(JvmMemoryConfig.COMPACT_HEADERS);
    for (String c :
        List.of(OrderGraph.LineItem.class.getName(), OrderGraph.Order.class.getName())) {
      long classChange =
          estimate.classes().stream()
              .filter(ce -> ce.className().equals(c))
              .mapToLong(FootprintEstimate.ClassEstimate::delta)
              .sum();
      long credited =
          compact.sites().stream()
              .filter(s -> s.className().equals(c) && s.location().equals(ORDER_METHOD))
              .mapToLong(AllocationAttribution.SiteSaving::delta)
              .sum();
      assertTrue(classChange < 0, c + " shrinks under compact headers");
      assertEquals(classChange, credited, c);
    }
  }

  @Test
  void sitesPlusCallersPlusUnattributedIsExactlyTheEstimatedChange() {
    for (JvmMemoryConfig target : attributions.keySet()) {
      AllocationAttribution a = attributions.get(target);
      FootprintEstimate e = estimates.get(target);
      assertEquals(
          e.bytesAfter() - e.bytesBefore(),
          a.attributedDelta() + a.callerDelta() + a.unattributedDelta(),
          target.toString());
    }
  }

  @Test
  void theAgentStopsCountingOnceDetached() throws Exception {
    // capture() sent "stop": the transformer is gone and OrderGraph's original bytecode is back,
    // so while ChurningOrders keeps building orders, the recorder's counts no longer move
    Path agent = AgentJar.build();
    Path first = Files.createTempFile("after-stop-", ".txt");
    Path second = Files.createTempFile("after-stop-", ".txt");
    VirtualMachine vm = VirtualMachine.attach(String.valueOf(target.pid()));
    try {
      vm.loadAgent(agent.toString(), "report:" + first);
      TimeUnit.MILLISECONDS.sleep(500);
      vm.loadAgent(agent.toString(), "report:" + second);
    } finally {
      vm.detach();
    }
    assertEquals(Files.readAllLines(first), Files.readAllLines(second));
    assertTrue(target.isAlive(), "the application keeps running after the detach");
  }

  private void awaitReady() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      if (Files.readString(targetOutput).contains("READY")) {
        return;
      }
      if (!target.isAlive()) {
        break;
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }
    throw new IllegalStateException(
        "target never became ready:\n" + Files.readString(targetOutput));
  }

  private static long count(Map<String, List<AllocationSites.Site>> byClass, String className) {
    return byClass.getOrDefault(className, List.of()).stream()
        .mapToLong(AllocationSites.Site::count)
        .sum();
  }
}
