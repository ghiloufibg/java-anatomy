package dev.sevenrungs.productionjvm.startup;

/**
 * What one {@link ClassSharingMode} cost, summarized over several measured runs.
 *
 * @param bestWallMillis fastest process launch-to-exit time, measured by the parent - the number a
 *     container orchestrator's readiness clock sees
 * @param medianWallMillis median of the same runs
 * @param medianMainMillis median time spent inside {@code StartupWorkload.main} (class loading
 *     triggered by the workload, without JVM boot and teardown)
 * @param loadedClasses every class-load line in the same run's {@code -Xlog:class+load}, so it and
 *     {@code sharedClasses} share one denominator
 * @param sharedClasses of {@code -Xlog:class+load}'s lines, those served from a CDS/AOT archive
 * @param workloadClassShared whether the application's own class came from the archive
 * @param metaspaceUsedKb Metaspace in use at the end: archived classes' metadata lives in the
 *     mapped archive instead, which is file-backed and shareable between JVMs on the same host
 * @param archiveBytes size of the archive this mode reads, 0 when it has none of its own
 */
public record StartupResult(
    ClassSharingMode mode,
    long bestWallMillis,
    long medianWallMillis,
    long medianMainMillis,
    int loadedClasses,
    int sharedClasses,
    boolean workloadClassShared,
    long metaspaceUsedKb,
    long classSpaceUsedKb,
    long archiveBytes) {

  /** Fraction (0..1) of loaded classes that came from an archive. */
  public double sharedFraction() {
    return loadedClasses == 0 ? 0 : sharedClasses / (double) loadedClasses;
  }
}
