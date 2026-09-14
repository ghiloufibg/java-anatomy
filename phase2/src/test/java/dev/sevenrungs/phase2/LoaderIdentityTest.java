package dev.sevenrungs.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sevenrungs.phase2.LoaderIdentity.IsolatingLoader;
import dev.sevenrungs.phase2.LoaderIdentity.Payload;
import org.junit.jupiter.api.Test;

class LoaderIdentityTest {

  @Test
  void sameBytesTwoLoadersAreTwoDistinctClasses() throws Exception {
    Class<?> a = new IsolatingLoader("plugin-A").loadClass(Payload.class.getName());
    Class<?> b = new IsolatingLoader("plugin-B").loadClass(Payload.class.getName());

    assertNotEquals(a, b, "different defining loaders must produce different runtime classes");
    assertEquals(a.getName(), b.getName(), "the binary name is identical, only identity differs");
  }

  @Test
  void castingAcrossLoadersThrowsClassCastException() throws Exception {
    Class<?> a = new IsolatingLoader("plugin-A").loadClass(Payload.class.getName());
    Object instanceFromPluginA = a.getConstructor().newInstance();

    // instanceFromPluginA's runtime class is "plugin-A's Payload", not this test's Payload -
    // even though they share a binary name, JVMS §5.3 makes them different classes.
    assertThrows(
        ClassCastException.class,
        () -> {
          Payload p = (Payload) instanceFromPluginA;
        });
  }

  @Test
  void methodInvocationStillWorksViaReflection() throws Exception {
    Class<?> a = new IsolatingLoader("plugin-A").loadClass(Payload.class.getName());
    Object instance = a.getConstructor().newInstance();

    Object result = a.getMethod("hello").invoke(instance);
    assertEquals(String.class, result.getClass());
    assertTrue(((String) result).startsWith("hi from"));
  }
}
