package dev.sevenrungs.languageevolution.exercise;

// "Type checking" for this language is really shape checking: every Var must name one of the
// arrays the caller is about to pass in. Small, but it exercises the same exhaustive-switch
// discipline as everything more elaborate a bigger language's checker would need.
import java.util.Set;

public final class TypeChecker {

  private TypeChecker() {}

  public static final class UnknownVariableException extends RuntimeException {
    public UnknownVariableException(String name) {
      super("unknown variable: " + name);
    }
  }

  /**
   * Walks {@code expr} and throws {@link UnknownVariableException} for the first {@link Var} that
   * doesn't name one of {@code knownVariables}. Exhaustive over every {@link TypedExpr} kind:
   * adding a node type to the sealed interface makes this switch fail to compile until it's handled
   * here too.
   */
  public static void check(TypedExpr expr, Set<String> knownVariables) {
    switch (expr) {
      case Const c -> {}
      case Var(var name) -> {
        if (!knownVariables.contains(name)) throw new UnknownVariableException(name);
      }
      case Add(var l, var r) -> {
        check(l, knownVariables);
        check(r, knownVariables);
      }
      case Sub(var l, var r) -> {
        check(l, knownVariables);
        check(r, knownVariables);
      }
      case Mul(var l, var r) -> {
        check(l, knownVariables);
        check(r, knownVariables);
      }
      case Div(var l, var r) -> {
        check(l, knownVariables);
        check(r, knownVariables);
      }
    }
  }
}
