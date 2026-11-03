package com.kreview.kreview.extraction;

import static org.junit.jupiter.api.Assertions.*;
import static com.kreview.kreview.extraction.RuleModel.*;

import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.document.SourceSpan;
import com.kreview.kreview.matching.RuleChangeAnalyzer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleBoundaryTests {
  private final RuleObligationExtractor extractor = new RuleObligationExtractor();
  private final RuleChangeAnalyzer analyzer = new RuleChangeAnalyzer();

  @Test
  void preservesUtf16AndRejectsSplitOrIncorrectEvidence() {
    var doc = new SourceDocument("v1", "😀\r\n尾款");
    assertEquals(new SourceSpan("v1", 4, 6, "尾款"), doc.span(4, 6));
    assertEquals("😀", doc.span(0, 2).quote());
    assertThrows(IllegalArgumentException.class, () -> doc.span(1, 2));
    assertThrows(IllegalArgumentException.class, () -> doc.span(0, 1));
    assertThrows(IllegalArgumentException.class, () -> doc.span(-1, 2));
    assertThrows(IllegalArgumentException.class, () -> doc.span(2, 8));
    assertThrows(IllegalArgumentException.class, () -> new SourceSpan("v1", 4, 6, "首款").validateAgainst(doc));
    assertThrows(IllegalArgumentException.class, () -> new SourceSpan("v2", 4, 6, "尾款").validateAgainst(doc));
    assertEquals("", doc.span(6, 6).quote());
  }

  @ParameterizedTest
  @ValueSource(strings = {"\uD83D", "\uDE00", "A\u0000B"})
  void rejectsInvalidDecodedText(String text) {
    assertThrows(IllegalArgumentException.class, () -> new SourceDocument("v1", text));
  }

  @Test
  void boundsInputWithoutTruncation() {
    assertThrows(IllegalArgumentException.class, () -> new SourceDocument("v1", "a".repeat(100_001)));
    assertThrows(IllegalArgumentException.class, () -> new SourceDocument("v1", "\n".repeat(5_001)));
    assertEquals(100_000, new SourceDocument("v1", "a".repeat(100_000)).text().length());
  }

  @Test
  void retainsDuplicateAndHierarchicalClauseKeys() {
    var doc = new SourceDocument("v1", "第三条 付款\r\n第2款：尾款。\r\n第三条 付款\n第2款：尾款。\n3.2 尾款。\n");
    var clauses = new ClauseSegmenter().segment(doc);
    assertEquals(List.of("3", "3.2", "3", "3.2", "3.2"), clauses.stream().map(Clause::key).toList());
    assertEquals(5, clauses.stream().map(Clause::id).distinct().count());
    clauses.forEach(c -> c.source().validateAgainst(doc));
  }

  @Test
  void normalizesSupportedOrdinalsButRejectsMalformedNumerals() {
    assertEquals("10", ClauseSegmenter.ordinal("十"));
    assertEquals("20", ClauseSegmenter.ordinal("二十"));
    assertEquals("101", ClauseSegmenter.ordinal("一百零一"));
    assertNull(ClauseSegmenter.ordinal("一二"));
    assertNull(ClauseSegmenter.ordinal("零"));
    assertNull(ClauseSegmenter.ordinal("1000"));
  }

  @Test
  void convertsExplicitYuanUnitsExactlyWithoutFloatingPoint() {
    var value = amount("第一条 合同总价人民币1.25万元。");
    assertEquals("CNY:12500", value.normalizedValue());
    assertEquals("人民币1.25万元", value.rawText());
    assertEquals("CNY:9999999999999999.99", amount("合同总价人民币9999999999999999.99元。").normalizedValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"人民币12,34元", "人民币12.345元", "人民币壹佰万元", "人民币100元（大写贰佰元）"})
  void doesNotInventMoneyNormalizationForUnsupportedValues(String raw) {
    var value = amount("第一条 合同总价" + raw + "。");
    assertEquals(FieldStatus.UNPARSED, value.status());
    assertNull(value.normalizedValue());
    assertFalse(value.sources().isEmpty());
  }

  @Test
  void invalidCalendarDateIsNotNormalized() {
    var extraction = extractor.extract(doc("v1", "第四条 第一批设备应于2026年2月30日交付。"));
    var value = extraction.obligations().getFirst().value(Field.DELIVERY_DEADLINE);
    assertEquals(FieldStatus.UNPARSED, value.status());
    assertNull(value.normalizedValue());
  }

  @Test
  void sameAndEmptyDocumentsDoNotInventChanges() {
    String text = "第一条 合同总价人民币100元。\n";
    assertTrue(analyzer.analyze(doc("v1", text), doc("v2", text), Relation.REVISION).changes().isEmpty());
    var empty = analyzer.analyze(doc("v1", ""), doc("v2", ""), Relation.REVISION);
    assertTrue(empty.changes().isEmpty());
    assertTrue(empty.findings().isEmpty());
    var added = analyzer.analyze(doc("v1", ""), doc("v2", text), Relation.REVISION);
    assertTrue(added.changes().isEmpty());
    assertEquals("NO_EXACT_ITEM_MATCH", added.findings().getFirst().reasonCode());
    assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(doc("v1", text), doc("v1", text), Relation.REVISION));
  }

  @Test
  void exactUnnumberedItemIdentityCanMatchButGenericCategoryCannot() {
    var exact = analyzer.analyze(doc("v1", "尾款人民币50元。"), doc("v2", "尾款人民币60元。"), Relation.REVISION);
    assertEquals(Method.EXACT_ITEM_KEY, exact.changes().getFirst().method());
    var generic = analyzer.analyze(doc("v1", "应于验收后十日支付人民币50元。"),
        doc("v2", "应于验收后二十日支付人民币50元。"), Relation.REVISION);
    assertTrue(generic.changes().isEmpty());
    assertEquals(TargetStatus.UNRESOLVED, generic.findings().getFirst().targetStatus());
  }

  @Test
  void referenceToOneClauseWithTwoPaymentsNeedsAnItemIdentity() {
    var result = supplement("第三条 首付款人民币100元，验收后十日支付；尾款人民币200元，验收后十日支付。",
        "原合同第三条的付款期限由验收后十日修改为验收后二十日。");
    assertTrue(result.changes().isEmpty());
    assertEquals("MULTIPLE_TARGET_ITEMS", result.findings().getFirst().reasonCode());
    assertEquals(2, result.findings().getFirst().candidateTargets().size());
  }

  @Test
  void checksQuotedOldValueAgainstTheActualTarget() {
    var result = supplement("第三条 尾款于验收后三十日支付。", "原合同第三条的尾款付款期限由验收后十日修改为验收后二十日。");
    assertTrue(result.changes().isEmpty());
    assertEquals("OLD_VALUE_MISMATCH", result.findings().getFirst().reasonCode());
  }

  @Test
  void preservesMultipleReferencesInsteadOfPickingOne() {
    var result = supplement("第三条 尾款于验收后十日支付。\n第四条 第一批应于2026年11月1日交付。",
        "原合同第三条及原合同第四条的付款期限由验收后十日修改为验收后二十日。");
    assertTrue(result.changes().isEmpty());
    assertEquals("MULTIPLE_REFERENCES", result.findings().getFirst().reasonCode());
  }

  @Test
  void supplementDoesNotDeleteUnmentionedFields() {
    var result = supplement("第三条 尾款人民币100元，于验收后十日支付。",
        "原合同第三条的尾款付款期限由验收后十日修改为验收后二十日。");
    assertEquals(List.of(Field.PAYMENT_DEADLINE), result.changes().stream().map(Change::field).toList());
    assertEquals("CNY:100", result.baseExtraction().obligations().getFirst().value(Field.AMOUNT).normalizedValue());
    assertTrue(result.nextExtraction().obligations().isEmpty());
  }

  @Test
  void duplicateNumberInRevisionIsNotForcedToOneTarget() {
    var result = analyzer.analyze(doc("v1", "第三条 尾款人民币100元。\n第三条 尾款人民币200元。"),
        doc("v2", "第三条 尾款人民币300元。"), Relation.REVISION);
    assertTrue(result.changes().isEmpty());
    assertEquals("DUPLICATE_CLAUSE_ID", result.findings().getFirst().reasonCode());
  }

  @Test
  void resultsAndSourceMapsAreImmutable() {
    var result = extractor.extract(doc("v1", "第一条 合同总价人民币100元。"));
    assertThrows(UnsupportedOperationException.class, () -> result.obligations().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.obligations().getFirst().fields().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.obligations().getFirst().value(Field.AMOUNT).sources().clear());
  }

  @Test
  void doesNotChooseTheLastOfTwoNamedPaymentTargets() {
    var result = supplement("第三条 首付款于验收后十日支付；尾款于验收后十日支付。",
        "原合同第三条的首付款和尾款付款期限由验收后十日修改为验收后二十日。");
    assertTrue(result.changes().isEmpty());
    assertEquals("MULTIPLE_ITEM_LABELS", result.findings().getFirst().reasonCode());
  }

  @Test
  void doesNotAcceptAParsedPrefixAsTheEntireNewDeadline() {
    var result = supplement("第三条 尾款于验收后十日支付。",
        "原合同第三条的尾款付款期限由验收后十日修改为验收后二十日或三十日。");
    assertTrue(result.changes().isEmpty());
    assertEquals("UNSUPPORTED_VALUE", result.findings().getFirst().reasonCode());
  }

  @Test
  void negatedChangeIsNotInterpretedAsAnAffirmativeAmendment() {
    var result = supplement("第三条 尾款于验收后十日支付。",
        "双方不同意将原合同第三条的尾款付款期限由验收后十日修改为验收后二十日。");
    assertTrue(result.changes().isEmpty());
    assertEquals("UNSUPPORTED_QUALIFIER", result.findings().getFirst().reasonCode());
  }

  @Test
  void conflictingChangesInOneSupplementAreNotTwoUniqueConclusions() {
    var result = supplement("第三条 尾款于验收后十日支付。",
        "原合同第三条的尾款付款期限由验收后十日修改为验收后二十日。\n"
        + "原合同第三条的尾款付款期限由验收后十日修改为验收后三十日。");
    assertTrue(result.changes().isEmpty());
    assertEquals(2, result.findings().size());
    assertTrue(result.findings().stream().allMatch(f -> f.reasonCode().equals("CONFLICTING_CHANGES")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"人民币-100元", "人民币.5元"})
  void malformedMoneyDoesNotBecomeAPositiveSuffixAmount(String raw) {
    var value = amount("第一条 合同总价" + raw + "。");
    assertEquals(FieldStatus.UNPARSED, value.status());
    assertNull(value.normalizedValue());
  }

  @Test
  void explicitAmountReferenceSupportsThousandsSeparators() {
    var result = supplement("第一条 合同总价人民币100,000元。",
        "原合同第一条的合同总价由人民币100,000元修改为人民币120,000元。");
    assertEquals(1, result.changes().size());
    assertEquals("CNY:120000", result.changes().getFirst().newValue().normalizedValue());
  }

  @Test
  void numberedClauseKeepsBlankLineContinuations() {
    var source = doc("v1", "第三条 付款\n\n尾款于验收后十日支付。\n");
    var clauses = new ClauseSegmenter().segment(source);
    assertEquals(1, clauses.size());
    assertEquals("3", clauses.getFirst().key());
    assertEquals(source.text(), clauses.getFirst().source().quote());
  }

  @Test
  void explicitlyUnknownFieldsRemainVisibleAndUnparsed() {
    assertEquals(FieldStatus.UNPARSED, amount("第一条 合同总价待定。").status());
    var result = extractor.extract(doc("v1", "第四条 交付期限另行协商。"));
    var deadline = result.obligations().getFirst().value(Field.DELIVERY_DEADLINE);
    assertEquals(FieldStatus.UNPARSED, deadline.status());
    assertNull(deadline.normalizedValue());
  }

  @Test
  void alternativeDeadlinesDoNotCollapseToTheFirstDate() {
    var result = extractor.extract(doc("v1", "第三条 尾款于验收后二十日或三十日支付。"));
    assertEquals(FieldStatus.AMBIGUOUS, result.obligations().getFirst().value(Field.PAYMENT_DEADLINE).status());
  }

  @Test
  void negatedBaseAmountIsNotAParsedFact() {
    var result = extractor.extract(doc("v1", "第一条 合同总价并非人民币100元。"));
    assertTrue(result.obligations().isEmpty());
    assertEquals("UNSUPPORTED_QUALIFIER", result.findings().getFirst().reasonCode());
  }

  @ParameterizedTest
  @ValueSource(strings = {"100元", "港币100元", "美元100元", "¥100元"})
  void doesNotGuessCnyFromAnUnspecifiedOrDifferentCurrency(String raw) {
    var value = amount("第一条 合同总价" + raw + "。");
    assertEquals(FieldStatus.UNPARSED, value.status());
    assertNull(value.normalizedValue());
    assertTrue(value.rawText().contains(raw));
  }

  @Test
  void aMissingFieldHasNoFabricatedNewEvidence() {
    var result = analyzer.analyze(doc("v1", "第三条 尾款人民币100元于验收后十日支付。"),
        doc("v2", "第三条 尾款于验收后二十日支付。"), Relation.REVISION);
    var amount = result.changes().stream().filter(c -> c.field() == Field.AMOUNT).findFirst().orElseThrow();
    assertEquals(FieldStatus.NOT_STATED, amount.newValue().status());
    assertTrue(amount.newValue().sources().isEmpty());
    assertNull(amount.newValue().normalizedValue());
  }

  private Analysis supplement(String base, String next) { return analyzer.analyze(doc("v1", base), doc("v2", next), Relation.SUPPLEMENT); }
  private SourceDocument doc(String id, String text) { return new SourceDocument(id, text); }
  private Value amount(String text) { return extractor.extract(doc("v1", text)).obligations().getFirst().value(Field.AMOUNT); }
}
