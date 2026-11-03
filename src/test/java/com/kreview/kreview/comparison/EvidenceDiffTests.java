package com.kreview.kreview.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kreview.kreview.document.SourceDocument;
import org.junit.jupiter.api.Test;

class EvidenceDiffTests {
  private final EvidenceDiff diff = new EvidenceDiff();
  private SourceDocument doc(String id, String text) { return new SourceDocument(id, text); }

  @Test void emojiAndCrLfKeepOriginalUtf16Offsets() {
    var oldDoc = doc("old", "价款😀十元\r\n");
    var newDoc = doc("new", "价款😀二十元\n");
    var result = diff.analyze(oldDoc, newDoc, "REVISION");
    assertThat(result.status()).isEqualTo("EXACT");
    assertThat(result.hunks()).hasSize(1);
    var h = result.hunks().get(0);
    assertThat(h.oldSource().quote()).isEqualTo(oldDoc.text());
    assertThat(h.newSource().quote()).isEqualTo(newDoc.text());
    assertThat(h.inlineStatus()).isEqualTo("EXACT");
    assertThat(h.inlineChanges()).isNotEmpty();
    h.inlineChanges().forEach(i -> {
      i.oldSource().validateAgainst(oldDoc);
      i.newSource().validateAgainst(newDoc);
    });
  }

  @Test void normalizedCrLfEqualityHasNoChanges() {
    assertThat(diff.analyze(doc("a", "甲\r\n"), doc("b", "甲\n"), "REVISION").hunks()).isEmpty();
  }

  @Test void terminalNewlineDifferenceKeepsExactSource() {
    var result = diff.analyze(doc("a", "甲"), doc("b", "甲\n"), "REVISION");
    assertThat(result.hunks()).singleElement().satisfies(h -> {
      assertThat(h.type()).isEqualTo(ChangeHunk.Type.REPLACE);
      assertThat(h.oldSource().quote()).isEqualTo("甲");
      assertThat(h.newSource().quote()).isEqualTo("甲\n");
    });
  }

  @Test void supplementNeverClaimsFullDeletion() {
    var result = diff.analyze(doc("a", "主合同内容"), doc("b", "补充协议内容"), "SUPPLEMENT");
    assertThat(result.status()).isEqualTo("NOT_APPLICABLE");
    assertThat(result.hunks()).isEmpty();
  }

  @Test void insertsDeletesAndRepeatedLinesMapToSource() {
    var a = doc("a", "同文\n第一条\n同文\n");
    var b = doc("b", "同文\n第二条\n同文\n新增\n");
    var result = diff.analyze(a, b, "REVISION");
    assertThat(result.hunks()).isNotEmpty();
    result.hunks().forEach(h -> { h.oldSource().validateAgainst(a); h.newSource().validateAgainst(b); });
    assertThat(result.hunks().stream().map(h -> h.newSource().quote()).toList()).anyMatch(s -> s.contains("新增"));
    assertThat(diff.analyze(doc("x", ""), doc("y", "a\n"), "REVISION").hunks().get(0).type())
        .isEqualTo(ChangeHunk.Type.INSERT);
    assertThat(diff.analyze(doc("x", "a\n"), doc("y", ""), "REVISION").hunks().get(0).type())
        .isEqualTo(ChangeHunk.Type.DELETE);
  }

  @Test void largeDifferentTextsUseBoundedWholeSourceFallback() {
    var a = doc("a", "甲\n".repeat(1001));
    var b = doc("b", "乙\n".repeat(1001));
    var result = diff.analyze(a, b, "REVISION");
    assertThat(result.status()).isEqualTo("BLOCK_FALLBACK");
    assertThat(result.hunks()).hasSize(1);
    result.hunks().get(0).oldSource().validateAgainst(a);
    result.hunks().get(0).newSource().validateAgainst(b);
    assertThatThrownBy(() -> new DiffEngine().compare(a.text(), b.text()))
        .isInstanceOf(DiffBudgetExceededException.class);
    assertThat(new DiffEngine().compare(a.text(), a.text())).isEmpty();
  }

  @Test void longSingleLineSkipsInlineButPreservesLineEvidence() {
    var result = diff.analyze(doc("a", "甲".repeat(2001)), doc("b", "乙".repeat(2001)), "REVISION");
    assertThat(result.hunks()).singleElement().extracting(EvidenceDiff.Hunk::inlineStatus).isEqualTo("SKIPPED_BUDGET");
  }
}
