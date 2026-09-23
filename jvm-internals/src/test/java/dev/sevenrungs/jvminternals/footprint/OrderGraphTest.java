package dev.sevenrungs.jvminternals.footprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

class OrderGraphTest {

  @Test
  void eachOrderAddsExactlyTheDocumentedNumberOfObjects() {
    // 200 orders already reach every shared pool entry, so the 201st adds only its own objects
    long twoHundred = GraphLayout.parseInstance((Object[]) OrderGraph.build(200)).totalCount();
    long twoHundredOne = GraphLayout.parseInstance((Object[]) OrderGraph.build(201)).totalCount();
    assertEquals(OrderGraph.OBJECTS_PER_ORDER, twoHundredOne - twoHundred);
  }

  @Test
  void ordersAreDeterministicSoEveryJvmBuildsTheSameHeap() {
    long first = GraphLayout.parseInstance(OrderGraph.order(42)).totalSize();
    long second = GraphLayout.parseInstance(OrderGraph.order(42)).totalSize();
    assertEquals(first, second);
  }
}
