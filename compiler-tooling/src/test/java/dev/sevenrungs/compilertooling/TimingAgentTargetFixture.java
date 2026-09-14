package dev.sevenrungs.compilertooling;

/** A tiny target for {@link TimingAgentTest} to instrument via a real forked {@code -javaagent}. */
public final class TimingAgentTargetFixture {
  public static void main(String[] args) {
    System.out.println("result=" + add(2, 3));
  }

  static int add(int a, int b) {
    return a + b;
  }
}
