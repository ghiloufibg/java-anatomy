package dev.sevenrungs.languageevolution;

// Sealed hierarchies + record patterns + exhaustive switch = algebraic data types (JEP 440/441).
// The compiler proves exhaustiveness (JLS §14.11.1.1), so adding a case is a compile error
// everywhere it matters, not a runtime surprise. Unnamed patterns from JEP 456.
public final class Algebra {

  sealed interface Expr permits Num, Add, Mul, Neg, Let, Var {}

  record Num(double v) implements Expr {}

  record Add(Expr l, Expr r) implements Expr {}

  record Mul(Expr l, Expr r) implements Expr {}

  record Neg(Expr e) implements Expr {}

  record Let(String name, Expr bound, Expr in) implements Expr {}

  record Var(String name) implements Expr {}

  /** Constant folding + algebraic simplification, one pass, no visitor boilerplate. */
  static Expr simplify(Expr e) {
    return switch (e) {
      case Num n -> n;
      case Add(Num(var a), Num(var b)) -> new Num(a + b);
      case Mul(Num(var a), Num(var b)) -> new Num(a * b);
      case Add(Num(var z), var r) when z == 0 -> simplify(r); // 0 + r
      case Add(var l, Num(var z)) when z == 0 -> simplify(l); // l + 0
      case Mul(Num(var o), var r) when o == 1 -> simplify(r); // 1 * r
      case Mul(Num(var z), _) when z == 0 -> new Num(0); // 0 * anything: JEP 456 unnamed pattern
      case Neg(Neg(var inner)) -> simplify(inner);
      case Neg(Num(var v)) -> new Num(-v);
      case Add(var l, var r) -> rebuild(new Add(simplify(l), simplify(r)), e);
      case Mul(var l, var r) -> rebuild(new Mul(simplify(l), simplify(r)), e);
      case Neg(var inner) -> rebuild(new Neg(simplify(inner)), e);
      case Let(var n, var b, var in) -> new Let(n, simplify(b), simplify(in));
      case Var v -> v;
    }; // remove a switch arm and this fails to compile: that's the feature.
    // Verified by hand while writing this class: deleting the "case Var v -> v;" arm above (Var
    // stays sealed-permitted) makes javac reject the switch with "the switch expression does not
    // cover all possible input values" - proof the exhaustiveness check is real, not
    // documentation. (Removing Var from `permits` instead is caught even earlier, as a different
    // error: "class is not allowed to extend sealed class: Expr", since Var still implements it.)
  }

  /** Re-run until fixpoint only when something changed (records give us structural equals). */
  static Expr rebuild(Expr next, Expr prev) {
    return next.equals(prev) ? next : simplify(next);
  }

  public static void main(String[] args) {
    Expr e =
        new Add(
            new Mul(new Num(1), new Var("x")), new Neg(new Neg(new Add(new Num(2), new Num(3)))));
    System.out.println(simplify(e)); // Add[l=Var[name=x], r=Num[v=5.0]]
  }
  // Next rungs: JEP 507 primitive patterns (`case int i when i > 0`), and JEP 468 derived
  // record creation (`p with { x = 0; }`) if your JDK previews them.
}
