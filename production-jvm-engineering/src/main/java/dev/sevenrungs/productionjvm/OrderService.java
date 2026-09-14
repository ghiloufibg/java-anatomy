package dev.sevenrungs.productionjvm;

// Your own JFR event. JFR (JEP 328) is the JDK's built-in profiler: ~1% overhead, always on
// in production is a normal choice. Custom events land in the same file as GC, JIT, locks.
// Run:  java -XX:StartFlightRecording=filename=orders.jfr,settings=profile OrderService
// Read: jfr print --events com.acme.OrderProcessed orders.jfr   |   jfr summary orders.jfr
import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

public final class OrderService {

  @Name("com.acme.OrderProcessed")
  @Label("Order Processed")
  @Category({"Acme", "Orders"})
  @Description("One order through the pricing pipeline")
  @StackTrace(false) // stack capture is the expensive part; opt in deliberately
  static final class OrderProcessed extends Event {
    @Label("Order id")
    String orderId;

    @Label("Line items")
    int lines;

    @Label("Total")
    @DataAmount // units annotate the UI, not the bytes
    long totalCents;

    @Label("Priced from cache")
    boolean cached;
  }

  static void process(String id, int lines) throws InterruptedException {
    var ev = new OrderProcessed(); // allocation is elided when the event is disabled
    ev.begin(); // start timestamp; duration computed at commit()
    Thread.sleep(1); // pretend work
    ev.orderId = id;
    ev.lines = lines;
    ev.totalCents = lines * 1_999L;
    ev.cached = lines % 3 == 0;
    ev.commit(); // written only if enabled and above the threshold
  }

  public static void main(String[] args) throws Exception {
    for (int i = 0; i < 500; i++) {
      process("o-" + i, 1 + i % 20);
    }
  }
}
// Then enable it with a .jfc settings file entry, or programmatically via
// Configuration.getConfiguration("profile") + Recording, and add a threshold so only slow
// orders are recorded. Thresholds and stack depth are the two knobs that keep overhead flat.
