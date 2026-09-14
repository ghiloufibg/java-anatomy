package dev.sevenrungs.languageevolution.exercise;

/** Element-wise division. */
public record Div(TypedExpr left, TypedExpr right) implements TypedExpr {}
