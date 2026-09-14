package dev.sevenrungs.phase2;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

/**
 * Object layout and object headers, read from the real heap with JOL (OpenJDK code-tools).
 *
 * <p>Compare:
 *
 * <pre>
 *   java                                LayoutProbe        # 12-byte header (compressed klass)
 *   java -XX:+UseCompactObjectHeaders   LayoutProbe        # 8-byte header (JEP 519, product in 25)
 *   java -XX:-UseCompressedOops -Xmx40g LayoutProbe        # what a &gt;32 GB heap costs you
 * </pre>
 *
 * <p>The mark word (JVMS says nothing about it; HotSpot: {@code
 * src/hotspot/share/oops/markWord.hpp}) carries hash, age, and lock state. Compact headers move the
 * klass pointer INTO the mark word.
 */
public final class LayoutProbe {
  static final class Node {
    boolean live;
    int id;
    long ts;
    Object payload;
    Node next;
  }

  public static void main(String[] args) {
    System.out.println(VM.current().details()); // oop size, header size, alignment
    System.out.println(ClassLayout.parseClass(Node.class).toPrintable());
    // Field reordering: HotSpot packs by size (longs, ints, shorts, bytes, then oops),
    // so `live` lands in a gap after `id`, not where you declared it.

    Node n = new Node();
    System.out.println(ClassLayout.parseInstance(n).toPrintable()); // mark word: unlocked, no hash
    System.identityHashCode(n);
    System.out.println(ClassLayout.parseInstance(n).toPrintable()); // hash now stored in mark word
    synchronized (n) {
      System.out.println(ClassLayout.parseInstance(n).toPrintable()); // lightweight-locked
    }
    // Lightweight locking (JDK 21+ default, JDK-8291555) keeps a lock stack per thread;
    // -XX:LockingMode was removed in 24 once legacy stack locking went away.

    Node head = n;
    for (int i = 0; i < 3; i++) {
      var m = new Node();
      m.next = head;
      head = m;
    }
    System.out.println(GraphLayout.parseInstance(head).toFootprint()); // reachable-graph cost
    // Follow-up worth a weekend: run with -Xlog:gc+heap=debug under G1 and map the region
    // table you get (Eden/Survivor/Old/Humongous) to JEP 248 and the G1 paper.
  }
}
