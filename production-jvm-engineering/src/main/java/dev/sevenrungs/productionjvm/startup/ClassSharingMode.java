package dev.sevenrungs.productionjvm.startup;

import java.nio.file.Path;
import java.util.List;

/**
 * The four ways a JDK 25 JVM can get its classes: from scratch, or pre-parsed from an archive
 * mapped straight into memory.
 *
 * <ul>
 *   <li>{@link #OFF} - {@code -Xshare:off}: every class is read from the JDK's modules image or the
 *       app JAR, parsed, verified and linked, and its metadata allocated in Metaspace.
 *   <li>{@link #DEFAULT_CDS} - no flags: the JDK ships a base CDS archive ({@code
 *       lib/server/classes.jsa}, JEP 341) covering ~1,300 core JDK classes. On by default since 12.
 *   <li>{@link #APP_CDS} - JEP 350 dynamic AppCDS: a training run with {@code
 *       -XX:ArchiveClassesAtExit} archives every class it loaded, JDK and application alike.
 *   <li>{@link #AOT_CACHE} - JEP 483 (24) with JEP 514's one-step {@code -XX:AOTCacheOutput} (25):
 *       classes are archived already <em>loaded and linked</em>, plus JEP 515 method profiles, so
 *       the JIT starts warm too.
 * </ul>
 *
 * <p>The archive-backed modes run with {@code -Xshare:on} / {@code -XX:AOTMode=on}: without them, a
 * stale or mismatched archive is silently ignored and you measure {@link #DEFAULT_CDS} thinking you
 * measured the archive. Strict mode turns that into a startup failure instead.
 */
public enum ClassSharingMode {
  OFF("CDS off", null, null, "-Xshare:off"),
  DEFAULT_CDS("default CDS (JDK base archive)", null, null),
  APP_CDS(
      "AppCDS (dynamic archive)",
      "app.jsa",
      "-XX:ArchiveClassesAtExit=",
      "-Xshare:on",
      "-XX:SharedArchiveFile="),
  AOT_CACHE(
      "AOT cache (JEP 483/514/515)",
      "app.aot",
      "-XX:AOTCacheOutput=",
      "-XX:AOTMode=on",
      "-XX:AOTCache=");

  private final String description;
  private final String archiveName;
  private final String trainingFlagPrefix;
  private final List<String> runFlags;

  ClassSharingMode(
      String description, String archiveName, String trainingFlagPrefix, String... runFlags) {
    this.description = description;
    this.archiveName = archiveName;
    this.trainingFlagPrefix = trainingFlagPrefix;
    this.runFlags = List.of(runFlags);
  }

  public String description() {
    return description;
  }

  /** Whether this mode needs a training run to produce its archive first. */
  public boolean needsTraining() {
    return archiveName != null;
  }

  /** The archive file this mode reads, inside {@code workDir}. */
  public Path archive(Path workDir) {
    return workDir.resolve(archiveName);
  }

  /** Flags for the training run that writes {@link #archive(Path)}. */
  public List<String> trainingFlags(Path workDir) {
    return List.of(trainingFlagPrefix + archive(workDir));
  }

  /** Flags for a measured run; a trailing {@code =} gets the archive path appended. */
  public List<String> runFlags(Path workDir) {
    return runFlags.stream().map(f -> f.endsWith("=") ? f + archive(workDir) : f).toList();
  }
}
