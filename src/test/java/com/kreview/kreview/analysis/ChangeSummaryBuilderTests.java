package com.kreview.kreview.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.extraction.RuleModel.*;
import com.kreview.kreview.matching.RuleChangeAnalyzer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ChangeSummaryBuilderTests {
  private final RuleChangeAnalyzer rules = new RuleChangeAnalyzer();
  private final ChangeSummaryBuilder builder = new ChangeSummaryBuilder();

  @Test void revisionAmountAndRelativeDeadlineRemainEvidenceBacked() throws Exception {
    var one = doc("F01", "base.txt", "v1"); var two = doc("F01", "next.txt", "v2");
    var amount = builder.build(rules.analyze(one, two, Relation.REVISION), one, two);
    assertThat(amount.summaryRows()).anySatisfy(row -> {
      assertThat(row.kind()).isEqualTo(Kind.CONTRACT_AMOUNT);
      assertThat(row.field()).isEqualTo(Field.AMOUNT);
      row.oldValue().sources().forEach(s -> s.validateAgainst(one));
      row.newValue().sources().forEach(s -> s.validateAgainst(two));
      assertThat(row.description()).contains("合同总价");
    });
    var dateBase = doc("F11", "base.txt", "v1"); var dateNext = doc("F11", "next.txt", "v2");
    var dates = builder.build(rules.analyze(dateBase, dateNext, Relation.REVISION), dateBase, dateNext).dateEntries();
    assertThat(dates.stream().filter(date -> date.rawText().contains("验收后十五日")).toList())
        .isNotEmpty().allSatisfy(date -> {
          assertThat(date.valueKind()).isEqualTo("RELATIVE");
          assertThat(date.isoDate()).isNull();
        });
    assertThat(dates).isSortedAccordingTo((a, b) -> {
      int bySequence = Integer.compare(a.sequence(), b.sequence());
      return bySequence == 0 ? Integer.compare(a.sources().getFirst().startUtf16(),
          b.sources().getFirst().startUtf16()) : bySequence;
    });
  }

  @Test void supplementExposesOnlyExplicitlyLinkedChangeAndUnresolvedEvidence() throws Exception {
    var one = doc("F04", "base.txt", "v1"); var two = doc("F04", "next.txt", "v2");
    var result = builder.build(rules.analyze(one, two, Relation.SUPPLEMENT), one, two);
    assertThat(result.summaryRows()).isNotEmpty();
    result.summaryRows().forEach(row -> {
      assertThat(row.method()).isEqualTo(Method.EXPLICIT_REFERENCE);
      assertThat(row.targetStatus()).isEqualTo(TargetStatus.UNIQUE);
      row.referenceEvidence().forEach(s -> s.validateAgainst(two));
    });
    assertThat(result.dateEntries()).anySatisfy(date -> {
      assertThat(date.sequence()).isEqualTo(2);
      assertThat(date.deadlineRole()).isEqualTo("PAYMENT_DEADLINE");
      assertThat(date.valueKind()).isEqualTo("RELATIVE");
    });
    var ambiguousBase = doc("F06", "base.txt", "v1"); var ambiguousNext = doc("F06", "next.txt", "v2");
    var unresolved = builder.build(rules.analyze(ambiguousBase, ambiguousNext, Relation.SUPPLEMENT), ambiguousBase, ambiguousNext);
    assertThat(unresolved.summaryRows()).isEmpty();
    assertThat(unresolved.unresolvedExpressions()).anySatisfy(f -> {
      assertThat(f.reasonCode()).isEqualTo("NO_EXPLICIT_REFERENCE");
      f.expression().validateAgainst(ambiguousNext);
    });
  }

  @Test void signingEffectiveAndEarlierSecondDateFollowFileOrder() {
    var base = new SourceDocument("v1", "签署日期：2026年10月1日。\n约定生效日期：2026年11月1日。\n");
    var next = new SourceDocument("v2", "签署日期：2025年1月1日。\n约定生效日期：2025年2月1日。\n");
    var dates = builder.build(rules.analyze(base, next, Relation.REVISION), base, next).dateEntries();
    assertThat(dates).hasSize(4);
    assertThat(dates.stream().map(ChangeSummaryBuilder.DateEntry::sequence)).containsExactly(1, 1, 2, 2);
    assertThat(dates.stream().map(ChangeSummaryBuilder.DateEntry::deadlineRole))
        .containsExactly("SIGNING_DATE", "EFFECTIVE_DATE", "SIGNING_DATE", "EFFECTIVE_DATE");
    assertThat(dates.get(2).isoDate()).isEqualTo("2025-01-01");
  }

  @Test void contractExpiryFollowingEffectiveWordingIsNotEffectiveDate() {
    var base = new SourceDocument("v1", "签订日期：2026年3月18日。\n");
    var next = new SourceDocument("v2", "自2026年10月1日起生效，有效期至 2027年3月31日 止。\n");
    var dates = builder.build(rules.analyze(base, next, Relation.SUPPLEMENT), base, next).dateEntries();
    assertThat(dates.stream().filter(date -> date.rawText().equals("2027年3月31日")).toList())
        .singleElement().extracting(ChangeSummaryBuilder.DateEntry::deadlineRole).isEqualTo("OTHER_DATE");
    assertThat(dates.stream().filter(date -> date.rawText().equals("2026年10月1日")).toList())
        .singleElement().extracting(ChangeSummaryBuilder.DateEntry::deadlineRole).isEqualTo("EFFECTIVE_DATE");
  }

  private SourceDocument doc(String id, String file, String version) throws Exception {
    try (var in = getClass().getResourceAsStream("/v1/" + id + "/" + file)) {
      return new SourceDocument(version, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
