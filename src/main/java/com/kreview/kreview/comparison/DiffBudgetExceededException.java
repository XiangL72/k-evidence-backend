package com.kreview.kreview.comparison;

/** The requested exact diff would exceed the bounded line-work budget. */
public final class DiffBudgetExceededException extends RuntimeException {
  public DiffBudgetExceededException() { super("Line diff exceeds work budget"); }
}
