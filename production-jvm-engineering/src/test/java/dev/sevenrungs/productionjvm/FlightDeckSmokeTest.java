package dev.sevenrungs.productionjvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The exercise's fast, always-run check: starts {@link OrderHttpService} for real, confined to a
 * small cgroup under G1 (the one collector guaranteed to behave identically everywhere - the full
 * three-collector run under sustained load is a manual, README-documented step, same as every other
 * phase's heavier benchmark), fires real HTTP requests at it, and asserts the flight deck's
 * exported metrics are all present and sane.
 */
class FlightDeckSmokeTest {

  private static final Pattern FLIGHTDECK_LINE =
      Pattern.compile(
          "FLIGHTDECK gcPauseCount=(\\d+) p99GcPauseUpperBoundMicros=(\\d+)"
              + " allocRateBytesPerSecond=([\\d.]+) monitorContentionMillis=(\\d+)"
              + " virtualThreadPinnedEvents=(\\d+)");

  @Test
  @Timeout(60)
  void flightDeckReportsRealMetricsForARealServiceUnderLoad() throws Exception {
    assumeTrue(
        ContainerCgroup.cgroupsAvailable(), "cgroup v1 cpu/memory controllers not writable here");

    try (var cgroup = ContainerCgroup.create("test-flightdeck")) {
      cgroup.setCpuQuota(100_000, 100_000); // 1 CPU
      cgroup.setMemoryLimitBytes(512L * 1024 * 1024);

      Process process =
          cgroup.startJava(
              List.of(
                  "-XX:+UseG1GC",
                  "-cp",
                  System.getProperty("java.class.path"),
                  "dev.sevenrungs.productionjvm.OrderHttpService",
                  "3", // durationSeconds
                  "0")); // ephemeral port
      try {
        var reader =
            new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder captured = new StringBuilder();
        // This sandbox's JAVA_TOOL_OPTIONS injects a "Picked up JAVA_TOOL_OPTIONS: ..." banner
        // line ahead of a child JVM's own output (merged into stdout by redirectErrorStream), so
        // the port announcement is not necessarily line 1 - scan forward for it instead.
        String line;
        Integer port = null;
        while (port == null && (line = reader.readLine()) != null) {
          captured.append(line).append('\n');
          if (line.startsWith("listening on ")) {
            port = Integer.parseInt(line.substring("listening on ".length()).trim());
          }
        }
        assertTrue(port != null, "expected a port announcement; output so far: " + captured);

        HttpClient client = HttpClient.newHttpClient();
        for (int i = 0; i < 20; i++) {
          HttpRequest request =
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/order"))
                  .GET()
                  .build();
          HttpResponse<String> response =
              client.send(request, HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          assertTrue(
              response.body().contains("orderId"), "expected a JSON body, got: " + response.body());
        }

        while ((line = reader.readLine()) != null) captured.append(line).append('\n');
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertTrue(finished, "service process did not exit within 30s");
        assertEquals(
            0, process.exitValue(), "service must exit cleanly; output so far: " + captured);

        Matcher m = FLIGHTDECK_LINE.matcher(captured);
        assertTrue(m.find(), "expected a FLIGHTDECK metrics line; output: " + captured);
        assertTrue(Long.parseLong(m.group(1)) >= 0, "gcPauseCount must not be negative");
        assertTrue(
            Double.parseDouble(m.group(3)) >= 0, "allocRateBytesPerSecond must not be negative");
        assertTrue(Long.parseLong(m.group(4)) >= 0, "monitorContentionMillis must not be negative");
        assertTrue(
            Long.parseLong(m.group(5)) >= 0, "virtualThreadPinnedEvents must not be negative");
      } finally {
        // Make sure a still-running child never outlives the cgroup try-with-resources below:
        // on any failure above (an assertion, a timeout) the cgroup directory can't be removed
        // while a live process is still a member of it, and destroyForcibly() itself does not
        // block for the process to actually die.
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }
}
