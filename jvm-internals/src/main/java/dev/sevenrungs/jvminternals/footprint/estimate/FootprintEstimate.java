package dev.sevenrungs.jvminternals.footprint.estimate;

import dev.sevenrungs.jvminternals.footprint.JvmMemoryConfig;
import java.util.Comparator;
import java.util.List;

/**
 * A predicted heap footprint under {@code target}, from a histogram captured under {@code source}.
 *
 * <p>Each class is predicted with the confidence its kind allows: plain objects exactly (the size
 * oracle knows their real layout), arrays as an estimate with a stated uncertainty (the histogram
 * gives their total bytes but not their individual lengths), unresolved classes not at all (their
 * bytes are carried over unchanged and reported, never silently dropped).
 */
public record FootprintEstimate(
    JvmMemoryConfig source, JvmMemoryConfig target, List<ClassEstimate> classes) {

  public enum Kind {
    /** A plain object: size from the oracle's real layout, exact. */
    OBJECT,
    /** An array estimated from its average length, assuming uniformly spread padding. */
    ARRAY,
    /** An array re-laid out from the real lengths of sampled live instances. */
    SAMPLED_ARRAY,
    /** A class the oracle couldn't load (hidden classes, lambdas): carried over unchanged. */
    UNRESOLVED
  }

  /**
   * @param uncertaintyBytes half-width of the band the true {@code bytesAfter} falls in; 0 for
   *     objects, whose size is exact
   */
  public record ClassEstimate(
      String className,
      Kind kind,
      long instances,
      long bytesBefore,
      long bytesAfter,
      long uncertaintyBytes) {
    public long delta() {
      return bytesAfter - bytesBefore;
    }
  }

  public FootprintEstimate {
    classes = List.copyOf(classes);
  }

  public long bytesBefore() {
    return classes.stream().mapToLong(ClassEstimate::bytesBefore).sum();
  }

  public long bytesAfter() {
    return classes.stream().mapToLong(ClassEstimate::bytesAfter).sum();
  }

  /** Predicted change in percent of the source heap; negative means memory saved. */
  public double changePercent() {
    return 100.0 * (bytesAfter() - bytesBefore()) / bytesBefore();
  }

  /** The part of the change that is exact: plain objects, sized by the oracle. */
  public long exactDelta() {
    return deltaOf(Kind.OBJECT);
  }

  /** The part of the change that is estimated: arrays, whose lengths the histogram doesn't give. */
  public long arrayDelta() {
    return deltaOf(Kind.ARRAY) + deltaOf(Kind.SAMPLED_ARRAY);
  }

  public long uncertaintyBytes() {
    return classes.stream().mapToLong(ClassEstimate::uncertaintyBytes).sum();
  }

  /** Bytes whose change couldn't be predicted at all (carried over as unchanged). */
  public long unresolvedBytes() {
    return classes.stream()
        .filter(c -> c.kind() == Kind.UNRESOLVED)
        .mapToLong(ClassEstimate::bytesBefore)
        .sum();
  }

  /** The {@code n} classes whose footprint changes the most, biggest absolute change first. */
  public List<ClassEstimate> topMovers(int n) {
    return classes.stream()
        .sorted(Comparator.comparingLong((ClassEstimate c) -> Math.abs(c.delta())).reversed())
        .limit(n)
        .toList();
  }

  private long deltaOf(Kind kind) {
    return classes.stream().filter(c -> c.kind() == kind).mapToLong(ClassEstimate::delta).sum();
  }
}
