package com.kreview.kreview.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.kreview.kreview.analysis.ChangeSummaryBuilder;
import com.kreview.kreview.classification.ClauseCategorizer;
import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.extraction.DocumentFactsExtractor;
import com.kreview.kreview.extraction.RuleObligationExtractor;
import com.kreview.kreview.extraction.RuleModel.Relation;
import com.kreview.kreview.matching.RuleChangeAnalyzer;
import org.junit.jupiter.api.Test;

class ComparisonMatrixBuilderTests {
  private final RuleChangeAnalyzer rules = new RuleChangeAnalyzer();
  private final ChangeSummaryBuilder summaries = new ChangeSummaryBuilder();
  private final DocumentFactsExtractor facts = new DocumentFactsExtractor();
  private final ComparisonMatrixBuilder builder = new ComparisonMatrixBuilder();

  private MatrixModel.Matrix matrix(String a, String b) {
    var old = new SourceDocument("a", a); var next = new SourceDocument("b", b);
    var analysis = rules.analyze(old, next, Relation.SUPPLEMENT);
    return builder.build("SUPPLEMENT", facts.extract(old, analysis.baseExtraction()),
        facts.extract(next, new RuleObligationExtractor().extract(next)), summaries.build(analysis, old, next));
  }

  @Test void unchangedBasePriceRemainsVisibleAndSupplementIsNotDeletion() {
    var result = matrix("第一条 合同总价人民币5,000元。\n第二条 交付期限2026年11月10日。",
        "原合同第二条交付期限由2026年11月10日修改为2026年11月20日。");
    assertThat(result.rows()).anySatisfy(row -> {
      if (!row.itemType().equals("TOTAL")) return;
      assertThat(row.cells().getFirst().displayText()).isEqualTo("人民币5,000元");
      assertThat(row.cells().get(1).status()).isEqualTo("NOT_DETECTED");
    });
    assertThat(result.rows().stream().filter(row -> row.itemType().equals("TOTAL"))).hasSize(1);
  }

  @Test void explicitIncreaseToLinksOldAndNewEvidenceWithoutArithmetic() {
    var result = matrix("第一条 合同总价人民币5,000元。",
        "原合同第一条合同总价增加至人民币6,000元。");
    var row = result.rows().stream().filter(r -> r.itemType().equals("TOTAL") && r.anchorItemId() != null)
        .findFirst().orElseThrow();
    assertThat(row.cells().get(1).status()).isEqualTo("CHANGED");
    assertThat(row.cells().get(1).displayText()).isEqualTo("人民币6,000元");
    assertThat(row.cells().get(1).oldSources().getFirst().quote()).isEqualTo("人民币5,000元");
    assertThat(row.cells().get(1).sources().getFirst().quote()).isEqualTo("人民币6,000元");
  }

  @Test void proposalAndIncrementRemainUnassociated() {
    for (String sentence : new String[]{"原合同第一条合同总价拟增加至人民币6,000元。",
        "原合同第一条合同总价增加人民币1,000元。"}) {
      var result = matrix("第一条 合同总价人民币5,000元。", sentence);
      assertThat(result.rows().stream().filter(r -> r.itemType().equals("TOTAL") && r.anchorItemId() != null)
          .findFirst().orElseThrow().cells().get(1).status()).isEqualTo("NOT_DETECTED");
      assertThat(result.unassociated()).isNotEmpty();
    }
  }

  @Test void unlinkedBContentAndSuspectedChangeAppearInMatrixWithoutClaimingAddition() {
    var result = matrix("第一条 合同总价人民币5,000元。",
        "原合同第一条合同总价拟增加至人民币6,000元。\n第二条 验收标准：逐台检查。");
    assertThat(result.rows().stream().filter(row -> row.cells().getFirst().status().equals("NO_ANCHOR")))
        .isNotEmpty().allSatisfy(row -> {
      assertThat(row.anchorItemId()).isNull();
      assertThat(row.cells().get(1).sources()).isNotEmpty();
      assertThat(row.cells().get(1).status()).isIn("TEXT_ONLY", "UNRESOLVED");
    });
    assertThat(result.rows().stream().filter(row -> row.cells().getFirst().status().equals("NO_ANCHOR")))
        .hasSize(result.unassociated().size());
    assertThat(result.rows().stream().filter(row -> row.cells().get(1).status().equals("CHANGED")))
        .isEmpty();
    assertThat(result.rows().stream().filter(row -> row.cells().getFirst().status().equals("NO_ANCHOR")
        && row.categoryKey().equals("ACCEPTANCE") && row.cells().get(1).displayText().contains("逐台检查")))
        .isNotEmpty();
  }

  @Test void identicalUnresolvedExpressionAndFactHaveOneBRow() {
    String sentence = "4.1 原合同第 3.4 条关于付款期限的约定修改为：甲方应于收到完整单据及发票之日起 45 日内付清运费";
    var result = matrix("3.4 甲方应于收到发票之日起 30 日内付清运费。", sentence + "。");
    assertThat(result.rows().stream().filter(row -> row.cells().getFirst().status().equals("NO_ANCHOR")
        && row.cells().get(1).displayText().equals(sentence))).hasSize(1);
  }

  @Test void explicitOldAmountMustAgreeAndChangeMustFollowReference() {
    for (String sentence : new String[]{
        "原合同第一条合同总价由人民币4,500元增加至人民币6,000元。",
        "合同总价增加至人民币6,000元，原合同第一条。"}) {
      var result = matrix("第一条 合同总价人民币5,000元。", sentence);
      assertThat(result.rows().stream().filter(r -> r.itemType().equals("TOTAL") && r.anchorItemId() != null)
          .findFirst().orElseThrow().cells().get(1).status()).isEqualTo("NOT_DETECTED");
      assertThat(result.unassociated()).isNotEmpty();
    }
  }

  @Test void matchingQuotedOldAmountAllowsIncrease() {
    var result = matrix("第一条 合同总价人民币5,000元。",
        "原合同第一条由人民币5,000元合同总价增加至人民币6,000元。");
    assertThat(result.rows().stream().filter(r -> r.itemType().equals("TOTAL") && r.anchorItemId() != null)
        .findFirst().orElseThrow().cells().get(1).status()).isEqualTo("CHANGED");
  }

  @Test void ownFileMetadataStayInHeadersAndAreNotComparedAsChanges() {
    var result = matrix("买方：甲公司\n签订日期：2026年10月1日。\n第一条 合同总价人民币5,000元。",
        "签订日期：2026年10月2日。\n原合同第一条合同总价增加至人民币6,000元。");
    assertThat(result.columns().getFirst().metadata()).extracting(DocumentFactsExtractor.FactItem::itemType)
        .contains("PARTIES", "SIGNED_ON");
    assertThat(result.columns().get(1).metadata()).extracting(DocumentFactsExtractor.FactItem::itemType)
        .contains("SIGNED_ON");
    assertThat(result.rows()).noneMatch(r -> r.categoryKey().equals("BASIC"));
  }

  @Test void labeledGoodsAndDatesRetainExactSources() {
    var doc = new SourceDocument("a", "买方：甲公司\n签订日期：2026年10月1日。\n货物名称：设备😀\n数量：10台\n规格：A型\n质量标准：合格\n");
    var extracted = facts.extract(doc, new RuleObligationExtractor().extract(doc));
    assertThat(extracted.metadata()).extracting(DocumentFactsExtractor.FactItem::itemType)
        .contains("PARTIES", "SIGNED_ON");
    assertThat(extracted.metadata().stream().filter(f -> f.itemType().equals("SIGNED_ON")).findFirst().orElseThrow()
        .normalizedValue()).isEqualTo("2026-10-01");
    assertThat(extracted.items()).extracting(DocumentFactsExtractor.FactItem::itemType)
        .contains("GOODS_NAME", "QUANTITY", "SPECIFICATION", "QUALITY");
    extracted.items().forEach(item -> item.sources().forEach(s -> s.validateAgainst(doc)));
  }

  @Test void synonymousDateLabelsRemainAmbiguousAndPartyRolesStaySeparate() {
    var doc = new SourceDocument("a", "买方：甲公司\n卖方：乙公司\n签订日期：2026年10月1日\n签署日期：2026年10月2日");
    var extracted = facts.extract(doc, new RuleObligationExtractor().extract(doc));
    assertThat(extracted.metadata().stream().filter(f -> f.itemType().equals("PARTIES")))
        .hasSize(2).allMatch(f -> !f.parseStatus().equals("AMBIGUOUS"));
    assertThat(extracted.metadata().stream().filter(f -> f.itemType().equals("SIGNED_ON")))
        .hasSize(2).allMatch(f -> f.parseStatus().equals("AMBIGUOUS") && f.normalizedValue() == null);
  }

  @Test void conflictingCategorySignalsFallBackToOther() {
    var categorizer = new ClauseCategorizer();
    var conflict = categorizer.classify("验收与违约责任：以原文为准");
    assertThat(conflict.categoryKey()).isEqualTo("OTHER");
    assertThat(conflict.candidateCategories()).containsExactly("ACCEPTANCE", "REMEDIES");
    assertThat(conflict.basis()).isEqualTo("HEADING_KEYWORD");
    assertThat(categorizer.category("验收标准：逐台检查")).isEqualTo("ACCEPTANCE");
  }
}
