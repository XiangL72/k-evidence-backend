package com.kreview.kreview.comparison;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DiffEngineTests {

  private final DiffEngine engine = new DiffEngine();

  @Test
  void sameTextAndCrLfOnlyDifferencesHaveNoChanges() {
    assertEquals(List.of(), engine.compare("", ""));
    assertEquals(List.of(), engine.compare(" \t甲😀\n尾", " \t甲😀\n尾"));
    assertEquals(List.of(), engine.compare("a\r\nb\r\n", "a\nb\n"));
  }

  @Test
  void insertionUsesEmptyOldRangeAtActualInsertionPoint() {
    assertEquals(List.of(new ChangeHunk("chg-0001", ChangeHunk.Type.INSERT,
        new ChangeHunk.LineRange(1, 1), new ChangeHunk.LineRange(1, 2), "", "new\r\n")),
        engine.compare("a\r\nb\n", "a\nnew\r\nb\n"));
  }

  @Test
  void deletionUsesEmptyNewRangeAtActualDeletionPoint() {
    assertEquals(List.of(new ChangeHunk("chg-0001", ChangeHunk.Type.DELETE,
        new ChangeHunk.LineRange(1, 2), new ChangeHunk.LineRange(1, 1), "old\r\n", "")),
        engine.compare("a\nold\r\nb\n", "a\r\nb\n"));
  }

  @Test
  void replacementSlicesOriginalCrLfAndUsesEndExclusiveRanges() {
    assertEquals(List.of(new ChangeHunk("chg-0001", ChangeHunk.Type.REPLACE,
        new ChangeHunk.LineRange(1, 3), new ChangeHunk.LineRange(1, 2),
        "old\r\nsecond\n", "new\r\n")),
        engine.compare("head\r\nold\r\nsecond\nend", "head\nnew\r\nend"));
  }

  @Test
  void emptyAndWhitespaceSidesAreNotTrimmed() {
    assertEquals(List.of(new ChangeHunk("chg-0001", ChangeHunk.Type.INSERT,
        new ChangeHunk.LineRange(0, 0), new ChangeHunk.LineRange(0, 2), "", " \t\r\n\n")),
        engine.compare("", " \t\r\n\n"));
    assertEquals(List.of(new ChangeHunk("chg-0001", ChangeHunk.Type.DELETE,
        new ChangeHunk.LineRange(0, 1), new ChangeHunk.LineRange(0, 0), " \t", "")),
        engine.compare(" \t", ""));
  }

  @Test
  void missingFinalNewlineAndLoneCrRemainVisible() {
    assertEquals(List.of(new ChangeHunk("chg-0001", ChangeHunk.Type.REPLACE,
        new ChangeHunk.LineRange(0, 1), new ChangeHunk.LineRange(0, 1), "a", "a\r\n")),
        engine.compare("a", "a\r\n"));
    assertFalse(engine.compare("a\r", "a").isEmpty());
    assertFalse(engine.compare("a\r", "a\n").isEmpty());
    assertFalse(engine.compare("a\n", "a\n\n").isEmpty());
  }

  @Test
  void idsAndResultsAreStableForFixedInputsAndVersion() {
    String before = "a\r\nb\nc\nd\n";
    String after = "a\nb2\r\nc\nd2\n";
    var result = engine.compare(before, after);
    assertEquals(List.of("chg-0001", "chg-0002"), result.stream().map(ChangeHunk::changeId).toList());
    for (int repeat = 0; repeat < 10; repeat++) {
      assertEquals(result, new DiffEngine().compare(before, after));
    }
    assertThrows(UnsupportedOperationException.class, () -> result.clear());
  }

  @Test
  void htmlFixturePreservesTextOnlyAndDoesNotTestBrowserSafety() {
    String before = "<script>alert('old')</script>\r\n";
    String after = "<img src=x onerror=alert('new')>\n";
    var result = engine.compare(before, after);
    assertEquals(1, result.size());
    assertEquals(before, result.get(0).oldText());
    assertEquals(after, result.get(0).newText());
  }

  @Test
  void rejectsNullAndNonPositiveLimitsWithoutLeakingDocumentText() {
    assertThrows(IllegalArgumentException.class, () -> engine.compare(null, ""));
    assertThrows(IllegalArgumentException.class, () -> engine.compare("", null));
    assertThrows(IllegalArgumentException.class, () -> new DiffEngine(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new DiffEngine(1, -1));
    var error = assertThrows(IllegalArgumentException.class,
        () -> new DiffEngine(1, 1).compare("secret document", ""));
    assertFalse(error.getMessage().contains("secret document"));
  }

  @Test
  void enforcesUtf16LimitOnBothSidesIncludingCrLfAndEmoji() {
    var bounded = new DiffEngine(2, 2);
    assertFalse(bounded.compare("", "😀").isEmpty());
    assertFalse(bounded.compare("\r\n", "").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> bounded.compare("😀a", ""));
    assertThrows(IllegalArgumentException.class, () -> bounded.compare("", "😀a"));
    String boundary = "x".repeat(100_000);
    assertFalse(engine.compare("", boundary).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> engine.compare(boundary + "x", ""));
    assertThrows(IllegalArgumentException.class, () -> engine.compare("", boundary + "x"));
  }

  @Test
  void enforcesLineLimitWithoutAddingSyntheticTrailingLine() {
    var bounded = new DiffEngine(100, 2);
    assertFalse(bounded.compare("", "a\nb\n").isEmpty());
    assertFalse(bounded.compare("a\nb", "").isEmpty());
    assertFalse(bounded.compare("", "a\rb\rc").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> bounded.compare("a\nb\nc", ""));
    assertThrows(IllegalArgumentException.class, () -> bounded.compare("", "a\nb\n\n"));
    String boundary = "x\n".repeat(5_000);
    assertFalse(engine.compare("", boundary).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> engine.compare(boundary + "\n", ""));
    assertThrows(IllegalArgumentException.class, () -> engine.compare("", boundary + "x"));
  }

  @ParameterizedTest
  @MethodSource("mappingFixtures")
  void mappingsAndReconstructionAreConsistentInBothDirections(String before, String after) {
    verifyMappingAndReconstruction(before, after);
    verifyMappingAndReconstruction(after, before);
  }

  static Stream<Arguments> mappingFixtures() {
    return Stream.of(
        Arguments.of("", ""),
        Arguments.of("", " \t\r\n\n"),
        Arguments.of("a\r\nb\n", "a\nb\r\n"),
        Arguments.of("a", "a\n"),
        Arguments.of("a\n", "a\n\n"),
        Arguments.of("a\n", "new\na\n"),
        Arguments.of("a\n", "a\nnew"),
        Arguments.of("same\nsame\nend", "same\nchanged\nsame\nend"),
        Arguments.of("a\nb\nc\n", "c\na\nb\n"),
        Arguments.of("付款期限30天。\r\n", "付款期限60天。\n"),
        Arguments.of("must comply", "may comply"),
        Arguments.of("must not share", "must share"),
        Arguments.of("甲😀\r\n尾\r", "甲🦊\n尾\r"),
        Arguments.of("<b>one</b>\n", "<script>two</script>\r\n"),
        Arguments.of("not 30\t\n", "not 30 \n"),
        Arguments.of("e\u0301", "é"),
        Arguments.of("head\r\nold\r\nend", "head\nnew\nextra\r\nend"));
  }

  @Test
  void smallRepeatedAndMixedEndingInputsReconstructExhaustively() {
    List<String> atoms = List.of("a\n", "b\r\n", "\n", "same", "\r", "😀\n");
    List<String> inputs = new ArrayList<>(List.of(""));
    inputs.addAll(atoms);
    for (String first : atoms) {
      for (String second : atoms) {
        inputs.add(first + second);
      }
    }
    for (String before : inputs) {
      for (String after : inputs) {
        verifyMappingAndReconstruction(before, after);
      }
    }
  }

  private void verifyMappingAndReconstruction(String before, String after) {
    List<String> oldLines = rawLines(before);
    List<String> newLines = rawLines(after);
    var result = engine.compare(before, after);
    assertEquals(result, engine.compare(before, after));
    StringBuilder rebuilt = new StringBuilder();
    int oldCursor = 0;
    int newCursor = 0;
    int ordinal = 1;
    for (var hunk : result) {
      var oldRange = hunk.oldRange();
      var newRange = hunk.newRange();
      assertTrue(oldRange.startLine() >= oldCursor);
      assertTrue(newRange.startLine() >= newCursor);
      assertTrue(oldRange.endLineExclusive() <= oldLines.size());
      assertTrue(newRange.endLineExclusive() <= newLines.size());
      assertEquals("chg-" + String.format(java.util.Locale.ROOT, "%04d", ordinal++), hunk.changeId());
      assertEquals(join(oldLines, oldRange.startLine(), oldRange.endLineExclusive()), hunk.oldText());
      assertEquals(join(newLines, newRange.startLine(), newRange.endLineExclusive()), hunk.newText());
      String unchangedOld = strict(join(oldLines, oldCursor, oldRange.startLine()));
      String unchangedNew = strict(join(newLines, newCursor, newRange.startLine()));
      assertEquals(unchangedNew, unchangedOld);
      rebuilt.append(unchangedOld).append(strict(hunk.newText()));
      switch (hunk.type()) {
        case INSERT -> {
          assertEquals(oldRange.startLine(), oldRange.endLineExclusive());
          assertTrue(newRange.startLine() < newRange.endLineExclusive());
          assertEquals("", hunk.oldText());
        }
        case DELETE -> {
          assertEquals(newRange.startLine(), newRange.endLineExclusive());
          assertTrue(oldRange.startLine() < oldRange.endLineExclusive());
          assertEquals("", hunk.newText());
        }
        case REPLACE -> {
          assertTrue(oldRange.startLine() < oldRange.endLineExclusive());
          assertTrue(newRange.startLine() < newRange.endLineExclusive());
        }
      }
      oldCursor = oldRange.endLineExclusive();
      newCursor = newRange.endLineExclusive();
    }
    assertEquals(strict(join(newLines, newCursor, newLines.size())),
        strict(join(oldLines, oldCursor, oldLines.size())));
    rebuilt.append(strict(join(oldLines, oldCursor, oldLines.size())));
    assertEquals(strict(after), rebuilt.toString());
  }

  private static List<String> rawLines(String text) {
    return text.isEmpty() ? List.of() : Arrays.asList(text.split("(?<=\n)"));
  }

  private static String join(List<String> lines, int start, int end) {
    return String.join("", lines.subList(start, end));
  }

  private static String strict(String text) {
    return text.replace("\r\n", "\n");
  }
}
