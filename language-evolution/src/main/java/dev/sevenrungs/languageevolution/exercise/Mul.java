package dev.sevenrungs.languageevolution.exercise;

/** Element-wise multiplication. */
public record Mul(TypedExpr left, TypedExpr right) implements TypedExpr {}
