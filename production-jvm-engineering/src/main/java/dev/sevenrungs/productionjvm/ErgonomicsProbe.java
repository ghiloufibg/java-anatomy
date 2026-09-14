package dev.sevenrungs.productionjvm;

// A tiny program whose only job is to report, from the inside, the ergonomic choices the JVM
// made from whatever cgroup it finds itself in: how many processors it thinks it has, how big it
// sized the heap, and which collector it picked. Reading these back from Runtime/ManagementFactory
// is more convincing (and more idiomatically Java) than grepping -XX:+PrintFlagsFinal's text dump
// for the same facts, and it is what ContainerCgroupTest actually asserts on.
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

public final class ErgonomicsProbe {
  public static void main(String[] args) {
    StringBuilder collectors = new StringBuilder();
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (!collectors.isEmpty()) collectors.append(',');
      collectors.append(bean.getName());
    }
    System.out.println("processors=" + Runtime.getRuntime().availableProcessors());
    System.out.println("maxMemoryBytes=" + Runtime.getRuntime().maxMemory());
    System.out.println("collectors=" + collectors);
  }
}
