package dev.sevenrungs.compilertooling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.invoke.MethodHandle;
import org.junit.jupiter.api.Test;

class IndyForgeTest {

  @Test
  void bootstrapLinksTheCallSiteExactlyOnce() throws Throwable {
    int before = IndyForge.linkCount.get();
    MethodHandle hello = IndyForge.forgeHello();

    assertEquals("Hello, Duke!", (String) hello.invokeExact("Duke"));
    assertEquals("Hello, Loom!", (String) hello.invokeExact("Loom"));
    assertEquals("Hello, Panama!", (String) hello.invokeExact("Panama"));

    assertEquals(1, IndyForge.linkCount.get() - before, "bootstrap must run exactly once per site");
  }

  @Test
  void eachForgedClassGetsItsOwnCallSite() throws Throwable {
    int before = IndyForge.linkCount.get();
    MethodHandle first = IndyForge.forgeHello();
    MethodHandle second = IndyForge.forgeHello();

    // invokeExact is signature-polymorphic: its expected type comes from the syntactic call
    // context, not the handle's own type. Used as a bare statement it infers a `void` return,
    // which then throws WrongMethodTypeException against these (String)String handles - found the
    // hard way while writing this test. Casting to String fixes the inferred descriptor.
    String a = (String) first.invokeExact("A");
    String b = (String) second.invokeExact("B");
    assertEquals("Hello, A!", a);
    assertEquals("Hello, B!", b);

    assertEquals(
        2, IndyForge.linkCount.get() - before, "each forged class links its own site once");
  }
}
