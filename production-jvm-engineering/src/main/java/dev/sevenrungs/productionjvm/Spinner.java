package dev.sevenrungs.productionjvm;

// A pure CPU burner: as many busy-looping platform threads as asked, for a fixed wall-clock
// duration. Used to reliably exceed a tight CFS quota and prove real throttling in cpu.stat -
// a single thread respects `nproc`, but the CFS bandwidth controller throttles the whole cgroup's
// aggregate usage, so more threads than the quota allows is what actually produces nr_throttled>0.
public final class Spinner {
  public static void main(String[] args) throws InterruptedException {
    int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 3;
    int threads =
        args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();
    long deadline = System.nanoTime() + seconds * 1_000_000_000L;

    Thread[] spinners = new Thread[threads];
    for (int i = 0; i < threads; i++) {
      spinners[i] =
          Thread.ofPlatform()
              .start(
                  () -> {
                    long x = 0;
                    while (System.nanoTime() < deadline) x++; // pure CPU burn, no allocation
                  });
    }
    for (Thread t : spinners) t.join();
    System.out.println("spun " + threads + " threads for " + seconds + "s");
  }
}
