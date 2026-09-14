package dev.sevenrungs.compilertooling;

/** The call {@link TimingAgent} inserts at the start of every instrumented method. */
public final class Recorder {
  private Recorder() {}

  public static void enter(String site) {
    System.out.println("ENTER " + site);
  }
}
