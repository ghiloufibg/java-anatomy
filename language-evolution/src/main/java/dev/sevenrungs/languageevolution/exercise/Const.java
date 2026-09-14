package dev.sevenrungs.languageevolution.exercise;

/** A literal value, the same at every index. */
public record Const(float value) implements TypedExpr {}
