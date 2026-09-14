package dev.sevenrungs.languageevolution.exercise;

/** Element-wise addition. */
public record Add(TypedExpr left, TypedExpr right) implements TypedExpr {}
