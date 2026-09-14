package dev.sevenrungs.languageevolution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sevenrungs.languageevolution.Algebra.Add;
import dev.sevenrungs.languageevolution.Algebra.Expr;
import dev.sevenrungs.languageevolution.Algebra.Let;
import dev.sevenrungs.languageevolution.Algebra.Mul;
import dev.sevenrungs.languageevolution.Algebra.Neg;
import dev.sevenrungs.languageevolution.Algebra.Num;
import dev.sevenrungs.languageevolution.Algebra.Var;
import org.junit.jupiter.api.Test;

class AlgebraTest {

  @Test
  void foldsTwoConstants() {
    assertEquals(new Num(5), Algebra.simplify(new Add(new Num(2), new Num(3))));
    assertEquals(new Num(6), Algebra.simplify(new Mul(new Num(2), new Num(3))));
  }

  @Test
  void dropsAdditiveIdentity() {
    assertEquals(new Var("x"), Algebra.simplify(new Add(new Num(0), new Var("x"))));
    assertEquals(new Var("x"), Algebra.simplify(new Add(new Var("x"), new Num(0))));
  }

  @Test
  void dropsMultiplicativeIdentity() {
    assertEquals(new Var("x"), Algebra.simplify(new Mul(new Num(1), new Var("x"))));
  }

  @Test
  void collapsesMultiplicationByZero() {
    assertEquals(new Num(0), Algebra.simplify(new Mul(new Num(0), new Var("x"))));
  }

  @Test
  void cancelsDoubleNegation() {
    assertEquals(new Var("x"), Algebra.simplify(new Neg(new Neg(new Var("x")))));
  }

  @Test
  void negatesANumberDirectly() {
    assertEquals(new Num(-5), Algebra.simplify(new Neg(new Num(5))));
  }

  @Test
  void simplifiesInsideLetBindings() {
    Expr let = new Let("x", new Add(new Num(1), new Num(1)), new Var("x"));
    assertEquals(new Let("x", new Num(2), new Var("x")), Algebra.simplify(let));
  }

  @Test
  void matchesTheArtifactsWorkedExample() {
    // Add(Mul(Num(1), Var(x)), Neg(Neg(Add(Num(2), Num(3))))) -> Add[l=Var[x], r=Num[5.0]]
    Expr e =
        new Add(
            new Mul(new Num(1), new Var("x")), new Neg(new Neg(new Add(new Num(2), new Num(3)))));
    assertEquals(new Add(new Var("x"), new Num(5)), Algebra.simplify(e));
  }
}
