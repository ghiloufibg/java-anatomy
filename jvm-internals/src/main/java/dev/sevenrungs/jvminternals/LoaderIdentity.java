package dev.sevenrungs.jvminternals;

import java.io.IOException;
import java.io.InputStream;

/**
 * Class identity = (loader, binary name). Same bytes, two loaders, two classes. This is JVMS §5.3:
 * "a class is determined by its binary name and its defining loader". Everything in plugin systems,
 * app servers and hot reload falls out of this rule.
 */
public final class LoaderIdentity {
  public static class Payload {
    public String hello() {
      return "hi from " + getClass().getClassLoader();
    }
  }

  /** Defines Payload from its own .class bytes instead of delegating to the parent. */
  static final class IsolatingLoader extends ClassLoader {
    IsolatingLoader(String name) {
      super(name, LoaderIdentity.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.equals(Payload.class.getName())) {
        return super.loadClass(name, resolve); // delegate
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        if (c == null) {
          try (InputStream in = getResourceAsStream(name.replace('.', '/') + ".class")) {
            byte[] b = in.readAllBytes();
            c = defineClass(name, b, 0, b.length); // JVMS §5.3.5: creation
          } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
          }
        }
        if (resolve) resolveClass(c);
        return c;
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Class<?> a = new IsolatingLoader("plugin-A").loadClass(Payload.class.getName());
    Class<?> b = new IsolatingLoader("plugin-B").loadClass(Payload.class.getName());
    System.out.println(a == b); // false
    System.out.println(a.getName().equals(b.getName())); // true: same name, different class
    Object pa = a.getConstructor().newInstance();
    try {
      Payload p = (Payload) pa; // ClassCastException: Payload (app loader) != Payload (plugin-A)
    } catch (ClassCastException e) {
      System.out.println("CCE: " + e.getMessage());
    }
    System.out.println(a.getMethod("hello").invoke(pa));
    // Now ask: why does the JVM also need "loader constraints" (JVMS §5.3.4)?
  }
}
