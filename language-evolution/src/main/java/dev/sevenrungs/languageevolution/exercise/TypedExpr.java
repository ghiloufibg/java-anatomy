package dev.sevenrungs.languageevolution.exercise;

// The exercise's sealed AST: a small numeric expression language, evaluated element-wise over
// named float[] arrays. Every node (Const, Var, Add, Sub, Mul, Div - each its own file, since a
// public sealed interface's public permitted types must each live in their own top-level file)
// is a record, so TypeChecker/ScalarEvaluator/VectorEvaluator all switch over the exact same
// shape - adding a node here means the compiler finds every switch that needs a new arm, in all
// three places, the same exhaustiveness guarantee Algebra.java demonstrates for the graded rung.
public sealed interface TypedExpr permits Const, Var, Add, Sub, Mul, Div {}
