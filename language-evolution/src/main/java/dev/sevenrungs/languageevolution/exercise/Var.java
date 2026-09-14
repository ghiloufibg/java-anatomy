package dev.sevenrungs.languageevolution.exercise;

/**
 * A reference to one of the caller's named {@code float[]} arrays, checked by {@link TypeChecker}.
 */
public record Var(String name) implements TypedExpr {}
