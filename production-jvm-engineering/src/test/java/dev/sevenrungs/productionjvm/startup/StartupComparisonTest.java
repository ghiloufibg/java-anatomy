package dev.sevenrungs.productionjvm.startup;

import static dev.sevenrungs.productionjvm.startup.ClassSharingMode.AOT_CACHE;
import static dev.sevenrungs.productionjvm.startup.ClassSharingMode.APP_CDS;
import static dev.sevenrungs.productionjvm.startup.ClassSharingMode.DEFAULT_CDS;
import static dev.sevenrungs.productionjvm.startup.ClassSharingMode.OFF;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Trains and runs {@link StartupWorkload} under every {@link ClassSharingMode} as real child JVMs
 * (~20 forks, ~15 s) and asserts what each archive actually buys: which classes it serves, how much
 * Metaspace it avoids, and that startup gets faster.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StartupComparisonTest {
  static final int RUNS = 3;

  @TempDir static Path workDir;
  private Map<ClassSharingMode, StartupResult> results;

  @BeforeAll
  @Timeout(300)
  void trainAndMeasureEveryMode() {
    results = StartupComparison.measureAll(workDir, RUNS);
  }

  @Test
  void withSharingOffNothingComesFromAnArchive() {
    StartupResult off = results.get(OFF);
    assertEquals(0, off.sharedClasses(), "-Xshare:off must load every class from scratch");
    assertFalse(off.workloadClassShared());
  }

  @Test
  void theJdksBaseArchiveCoversCoreClassesButNotTheApplication() {
    StartupResult cds = results.get(DEFAULT_CDS);
    // some JDK builds ship without lib/server/classes.jsa; that's a packaging choice, not a failure
    assumeTrue(cds.sharedClasses() > 0, "this JDK ships no default CDS archive");
    assertAll(
        () -> assertFalse(cds.workloadClassShared(), "the base archive knows nothing of our app"),
        () ->
            assertTrue(
                cds.sharedFraction() < 0.9,
                "XML, HTTP client, regex... aren't in the base archive, got "
                    + cds.sharedFraction()));
  }

  @ParameterizedTest
  @EnumSource(
      value = ClassSharingMode.class,
      names = {"APP_CDS", "AOT_CACHE"})
  void aTrainedArchiveServesNearlyEveryClassIncludingTheApplications(ClassSharingMode mode) {
    StartupResult r = results.get(mode);
    assertAll(
        () -> assertTrue(Files.size(mode.archive(workDir)) > 0, "training must write an archive"),
        () -> assertTrue(r.workloadClassShared(), "the app's own class must come from the archive"),
        () ->
            assertTrue(
                r.sharedFraction() >= 0.95,
                () ->
                    "%s served only %d of %d classes"
                        .formatted(mode, r.sharedClasses(), r.loadedClasses())));
  }

  @Test
  void archivedClassesKeepTheirMetadataOutOfMetaspace() {
    // archived class metadata lives in the mapped, file-backed archive (shareable across JVMs on
    // one host) instead of being built per process in Metaspace
    long off = results.get(OFF).metaspaceUsedKb();
    long appCds = results.get(APP_CDS).metaspaceUsedKb();
    long aot = results.get(AOT_CACHE).metaspaceUsedKb();
    assertAll(
        () ->
            assertTrue(
                appCds < off / 2, "AppCDS Metaspace %d KB vs off %d KB".formatted(appCds, off)),
        () ->
            assertTrue(
                aot < off / 2, "AOT cache Metaspace %d KB vs off %d KB".formatted(aot, off)));
  }

  @Test
  void theAotCacheStartsFasterThanLoadingEverythingFromScratch() {
    // best-of-N wall time, the least noisy single number; measured gap here is ~30%, so a plain
    // "faster" assertion leaves a lot of room for a noisy CI runner
    long off = results.get(OFF).bestWallMillis();
    long aot = results.get(AOT_CACHE).bestWallMillis();
    assertTrue(aot < off, "AOT cache %d ms vs CDS off %d ms".formatted(aot, off));
  }

  @ParameterizedTest
  @EnumSource(
      value = ClassSharingMode.class,
      names = {"APP_CDS", "AOT_CACHE"})
  void strictModeFailsLoudlyWhenTheArchiveIsMissing(ClassSharingMode mode) {
    // without -Xshare:on / -XX:AOTMode=on a missing or stale archive is silently ignored, and a
    // "with archive" benchmark quietly measures the default CDS instead
    Path jar = StartupComparison.packageWorkload(workDir);
    Path untrained = workDir.resolve("untrained-" + mode);
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> StartupComparison.run(jar, mode.runFlags(untrained)));
    assertTrue(failure.getMessage().contains("workload failed"), failure.getMessage());
  }
}
