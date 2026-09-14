package dev.sevenrungs.jvminternals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;

class LayoutProbeTest {
  static final class Empty {}

  @Test
  void instanceSizeIsPositiveAndObjectAligned() {
    // Every object's size is a multiple of the JVM's object alignment (default 8 bytes),
    // whether or not compact headers (JEP 519) are enabled - this is the one portable
    // invariant that doesn't depend on which header shape this particular JVM run picked.
    long size = ClassLayout.parseInstance(new Empty()).instanceSize();
    assertTrue(size > 0, "an object always occupies some space");
    assertEquals(0, size % 8, "object size must be a multiple of the alignment (8 bytes)");
  }

  @Test
  void identityHashCodeDoesNotChangeTheReportedInstanceSize() {
    // Once the identity hash is computed it moves into the mark word, but the mark word's
    // *size* never changes as a result - only its bit contents do.
    var n = new LayoutProbe.Node();
    long before = ClassLayout.parseInstance(n).instanceSize();
    System.identityHashCode(n);
    long after = ClassLayout.parseInstance(n).instanceSize();
    assertEquals(before, after);
  }

  @Test
  void graphFootprintGrowsAsMoreNodesAreLinkedIn() {
    LayoutProbe.Node head = new LayoutProbe.Node();
    long oneNode = GraphLayout.parseInstance(head).totalSize();

    for (int i = 0; i < 5; i++) {
      var m = new LayoutProbe.Node();
      m.next = head;
      head = m;
    }
    long sixNodes = GraphLayout.parseInstance(head).totalSize();

    assertTrue(sixNodes > oneNode, "linking more reachable nodes must grow the graph's footprint");
  }
}
