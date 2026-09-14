package dev.sevenrungs.languageevolution.exercise;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sevenrungs.languageevolution.exercise.TypeChecker.UnknownVariableException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypeCheckerTest {

  @Test
  void acceptsAnExpressionOverKnownVariables() {
    var expr = new Add(new Mul(new Var("x"), new Var("y")), new Const(1));
    assertDoesNotThrow(() -> TypeChecker.check(expr, Set.of("x", "y")));
  }

  @Test
  void rejectsAnUnknownVariable() {
    var expr = new Div(new Var("x"), new Var("z"));
    UnknownVariableException ex =
        assertThrows(UnknownVariableException.class, () -> TypeChecker.check(expr, Set.of("x")));
    assertTrue(ex.getMessage().contains("z"));
  }

  @Test
  void aBareConstantAlwaysChecksOut() {
    assertDoesNotThrow(() -> TypeChecker.check(new Const(42), Set.of()));
  }
}
