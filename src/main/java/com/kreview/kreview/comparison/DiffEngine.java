package com.kreview.kreview.comparison;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.myers.MyersDiff;
import com.github.difflib.patch.AbstractDelta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Pure Java STRICT line comparison, independent of Spring, database and model providers. */
public final class DiffEngine {

  public static final int DEFAULT_MAX_CHARACTERS = 100_000;
  public static final int DEFAULT_MAX_LINES = 5_000;
  public static final long MAX_LINE_WORK = 1_000_000;

  private final int maxCharacters;
  private final int maxLines;
  private final NormalizationService normalization = new NormalizationService();

  public DiffEngine() {
    this(DEFAULT_MAX_CHARACTERS, DEFAULT_MAX_LINES);
  }

  public DiffEngine(int maxCharacters, int maxLines) {
    if (maxCharacters <= 0 || maxLines <= 0) {
      throw new IllegalArgumentException("Input limits must be positive");
    }
    this.maxCharacters = maxCharacters;
    this.maxLines = maxLines;
  }

  public List<ChangeHunk> compare(String oldText, String newText) {
    validateInput(oldText, "oldText");
    validateInput(newText, "newText");
    var oldSource = normalization.normalize(oldText);
    var newSource = normalization.normalize(newText);
    if (oldSource.comparisonKeys().equals(newSource.comparisonKeys())) return List.of();
    if ((long) oldSource.lines().size() * newSource.lines().size() > MAX_LINE_WORK) {
      throw new DiffBudgetExceededException();
    }
    var deltas = new ArrayList<>(DiffUtils.diff(
        oldSource.comparisonKeys(), newSource.comparisonKeys(), new MyersDiff<String>()).getDeltas());
    deltas.sort(Comparator
        .comparingInt((AbstractDelta<String> delta) -> delta.getSource().getPosition())
        .thenComparingInt(delta -> delta.getTarget().getPosition()));

    List<ChangeHunk> result = new ArrayList<>();
    for (var delta : deltas) {
      var oldRange = new ChangeHunk.LineRange(
          delta.getSource().getPosition(), delta.getSource().getPosition() + delta.getSource().size());
      var newRange = new ChangeHunk.LineRange(
          delta.getTarget().getPosition(), delta.getTarget().getPosition() + delta.getTarget().size());
      var type = switch (delta.getType()) {
        case INSERT -> ChangeHunk.Type.INSERT;
        case DELETE -> ChangeHunk.Type.DELETE;
        case CHANGE -> ChangeHunk.Type.REPLACE;
        case EQUAL -> throw new IllegalStateException("Unexpected unchanged delta");
      };
      result.add(new ChangeHunk(String.format(Locale.ROOT, "chg-%04d", result.size() + 1),
          type, oldRange, newRange, oldSource.rawSlice(oldRange), newSource.rawSlice(newRange)));
    }
    return List.copyOf(result);
  }

  private void validateInput(String text, String side) {
    if (text == null) {
      throw new IllegalArgumentException(side + " must not be null");
    }
    if (text.length() > maxCharacters) {
      throw new IllegalArgumentException(side + " exceeds UTF-16 character limit: " + maxCharacters);
    }
    int lines = 0;
    for (int offset = 0; offset < text.length(); offset++) {
      if (text.charAt(offset) == '\n' && ++lines > maxLines) {
        throw new IllegalArgumentException(side + " exceeds line limit: " + maxLines);
      }
    }
    if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n' && ++lines > maxLines) {
      throw new IllegalArgumentException(side + " exceeds line limit: " + maxLines);
    }
  }
}
