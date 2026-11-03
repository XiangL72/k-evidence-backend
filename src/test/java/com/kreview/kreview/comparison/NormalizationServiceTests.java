package com.kreview.kreview.comparison;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NormalizationServiceTests {

  private final NormalizationService service = new NormalizationService();

  @Test
  void preservesRawOffsetsThroughMixedLineEndingsAndEmoji() {
    var source = service.normalize("甲\r\n😀\n尾\r");
    assertEquals(List.of("甲\n", "😀\n", "尾\r"), source.comparisonKeys());
    assertEquals(List.of(
        new NormalizationService.SourceLine(0, 3, "甲\n"),
        new NormalizationService.SourceLine(3, 6, "😀\n"),
        new NormalizationService.SourceLine(6, 8, "尾\r")), source.lines());
    assertEquals("甲\r\n😀\n", source.rawSlice(new ChangeHunk.LineRange(0, 2)));
    assertEquals("尾\r", source.rawSlice(new ChangeHunk.LineRange(2, 3)));
    assertEquals("", source.rawSlice(new ChangeHunk.LineRange(0, 0)));
    assertEquals("", source.rawSlice(new ChangeHunk.LineRange(1, 1)));
    assertEquals("", source.rawSlice(new ChangeHunk.LineRange(3, 3)));
  }

  @Test
  void emptyInputHasZeroLinesAndTrailingNewlineHasNoSyntheticLine() {
    assertEquals(List.of(), service.normalize("").comparisonKeys());
    assertEquals("", service.normalize("").rawSlice(new ChangeHunk.LineRange(0, 0)));
    assertEquals(List.of("a"), service.normalize("a").comparisonKeys());
    assertEquals(List.of("a\n"), service.normalize("a\n").comparisonKeys());
    assertEquals(List.of("\n"), service.normalize("\n").comparisonKeys());
    assertEquals(List.of("a\n", "\n"), service.normalize("a\n\n").comparisonKeys());
  }

  @Test
  void strictChangesOnlyCrLfPairs() {
    String input = " \tNot 30 <b>😀</b> e\u0301\rX\r\n\n";
    var normalized = service.normalize(input);
    assertEquals(input.replace("\r\n", "\n"), String.join("", normalized.comparisonKeys()));
    assertEquals(input, normalized.rawSlice(new ChangeHunk.LineRange(0, 2)));
  }

  @Test
  void rejectsNullAndInvalidSourceRanges() {
    assertThrows(IllegalArgumentException.class, () -> service.normalize(null));
    assertThrows(IllegalArgumentException.class, () -> new ChangeHunk.LineRange(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> new ChangeHunk.LineRange(2, 1));
    var source = service.normalize("one");
    assertThrows(IllegalArgumentException.class, () -> source.rawSlice(new ChangeHunk.LineRange(1, 2)));
    assertThrows(IllegalArgumentException.class, () -> source.rawSlice(new ChangeHunk.LineRange(2, 2)));
  }

  @Test
  void sourceLinesAndComparisonKeysAreImmutable() {
    var source = service.normalize("a\n");
    assertThrows(UnsupportedOperationException.class, () -> source.lines().clear());
    assertThrows(UnsupportedOperationException.class, () -> source.comparisonKeys().clear());
  }
}
