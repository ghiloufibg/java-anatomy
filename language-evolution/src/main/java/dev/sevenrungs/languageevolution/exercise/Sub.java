package dev.sevenrungs.languageevolution.exercise;

/** Element-wise subtraction. */
public record Sub(TypedExpr left, TypedExpr right) implements TypedExpr {}
