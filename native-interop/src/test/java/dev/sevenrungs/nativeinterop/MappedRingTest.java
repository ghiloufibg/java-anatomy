package dev.sevenrungs.nativeinterop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class MappedRingTest {

  private Path ringFile;

  @AfterEach
  void cleanup() throws IOException {
    if (ringFile != null) Files.deleteIfExists(ringFile);
  }

  @Test
  @Timeout(30)
  void producerAndConsumerAgreeOnTheSumInOneProcess() throws Exception {
    ringFile = Files.createTempFile("ring-inproc-", ".bin");
    long count = 1000; // small: this test only proves the protocol, not throughput

    try (Arena arena = Arena.ofShared()) {
      MemorySegment ring = MappedRing.mapRing(ringFile, arena);
      Thread producer = new Thread(() -> MappedRing.produce(ring, count));
      long[] result = new long[1];
      Thread consumer = new Thread(() -> result[0] = MappedRing.consume(ring, count));

      consumer.start();
      producer.start();
      producer.join();
      consumer.join();

      assertEquals(count * (count + 1) / 2, result[0]);
    }
  }

  @Test
  @Timeout(60)
  void producerAndConsumerAgreeAcrossTwoRealProcesses() throws Exception {
    // The guru point of this rung: two independent JVM processes, not two threads, so the
    // release/acquire VarHandle access modes are the only thing keeping their views of the mapped
    // file consistent - no shared Java heap, no monitors, nothing the JLS itself governs.
    ringFile = Files.createTempFile("ring-xproc-", ".bin");
    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("java.class.path");

    Process producer = startRingProcess(javaBin, classpath, "produce");
    Process consumer = startRingProcess(javaBin, classpath, "consume");

    String consumerOutput = awaitAndReadStdout(consumer, "consumer");
    awaitAndReadStdout(producer, "producer");

    // This sandbox's JVM launcher prepends a "Picked up JAVA_TOOL_OPTIONS: ..." banner to stdout;
    // MappedRing.main itself only ever prints the one line consume() produces, so take the last
    // non-blank line rather than the whole captured output.
    String[] lines = consumerOutput.strip().split("\\R");
    String lastLine = lines[lines.length - 1];
    assertEquals(String.valueOf(MappedRing.COUNT * (MappedRing.COUNT + 1) / 2), lastLine);
  }

  private Process startRingProcess(String javaBin, String classpath, String mode)
      throws IOException {
    return new ProcessBuilder(
            javaBin,
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            classpath,
            "dev.sevenrungs.nativeinterop.MappedRing",
            mode,
            ringFile.toString())
        .redirectErrorStream(true)
        .start();
  }

  private static String awaitAndReadStdout(Process process, String label)
      throws IOException, InterruptedException {
    String output = new String(process.getInputStream().readAllBytes());
    boolean finished = process.waitFor(45, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      fail(label + " process did not finish within 45s; output so far: " + output);
    }
    if (process.exitValue() != 0) {
      fail(label + " process exited with " + process.exitValue() + "; output: " + output);
    }
    return output;
  }
}
