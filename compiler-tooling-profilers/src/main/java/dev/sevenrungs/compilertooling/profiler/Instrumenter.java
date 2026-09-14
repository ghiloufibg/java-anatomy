package dev.sevenrungs.compilertooling.profiler;

/**
 * The shape all three implementations share: rewrite one class's bytecode so every {@code new},
 * {@code anewarray}, {@code newarray} and {@code multianewarray} instruction calls {@link
 * AllocationRecorder#record} immediately beforehand, keyed by {@code owner.method:allocatedType}.
 */
public interface Instrumenter {
  byte[] instrument(String ownerInternalName, byte[] original);
}
