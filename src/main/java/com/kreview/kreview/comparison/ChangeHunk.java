package com.kreview.kreview.comparison;

/** Application-owned line diff. Text is sliced from the corresponding original input. */
public record ChangeHunk(
    String changeId,
    Type type,
    LineRange oldRange,
    LineRange newRange,
    String oldText,
    String newText) {

  public enum Type {
    INSERT, DELETE, REPLACE
  }

  /** Zero-based, end-exclusive. An empty side is represented by [position, position). */
  public record LineRange(int startLine, int endLineExclusive) {
    public LineRange {
      if (startLine < 0 || endLineExclusive < startLine) {
        throw new IllegalArgumentException("Invalid line range");
      }
    }
  }
}
