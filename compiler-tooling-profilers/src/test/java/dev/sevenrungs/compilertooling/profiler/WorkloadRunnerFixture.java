package dev.sevenrungs.compilertooling.profiler;

/** Drives every {@link Workload} allocation site once, for {@link AllocationAgentTest}. */
public final class WorkloadRunnerFixture {
  public static void main(String[] args) {
    Workload.allocateObject();
    Workload.allocatePrimitiveArray(3);
    Workload.allocateReferenceArray(3);
    Workload.allocateMultiArray();
    System.out.println("workload complete");
    // AllocationAgent's shutdown hook prints the report as this process exits normally.
  }
}
