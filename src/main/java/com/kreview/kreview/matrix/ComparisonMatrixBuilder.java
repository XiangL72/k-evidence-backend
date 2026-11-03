package com.kreview.kreview.matrix;

import com.kreview.kreview.analysis.ChangeSummaryBuilder;
import com.kreview.kreview.document.SourceSpan;
import com.kreview.kreview.extraction.DocumentFactsExtractor.DocumentFacts;
import com.kreview.kreview.extraction.DocumentFactsExtractor.FactItem;
import com.kreview.kreview.matrix.MatrixModel.*;
import com.kreview.kreview.profile.GoodsSaleProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Pure projection from per-document facts and already verified changes. */
public final class ComparisonMatrixBuilder {
  public Matrix build(String relation, DocumentFacts base, DocumentFacts next,
      ChangeSummaryBuilder.Result summary) {
    List<Row> rows = new ArrayList<>();
    List<Unassociated> unassociated = new ArrayList<>();
    Set<String> usedChanges = new HashSet<>();
    Set<String> usedNextFactIds = new HashSet<>();
    for (var category : GoodsSaleProfile.CATEGORIES) {
      for (var item : base.items()) {
        if (!category.key().equals(item.categoryKey())) continue;
        var matches = summary.summaryRows().stream().filter(change -> matches(item, change)).toList();
        Cell nextCell;
        if (matches.size() == 1) {
          var change = matches.getFirst();
          usedChanges.add(change.rowId());
          nextCell = new Cell(next.versionId(), "CHANGED", change.newValue().rawText(),
              change.newValue().sources(), change.oldValue().sources(), change.referenceEvidence(), null);
          for (var bFact : next.items()) {
            if (bFact.sources().stream().anyMatch(s -> change.newValue().sources().stream().anyMatch(n -> overlaps(s, n)))) {
              usedNextFactIds.add(bFact.itemId());
            }
          }
        } else if (matches.size() > 1) {
          matches.forEach(m -> usedChanges.add(m.rowId()));
          nextCell = new Cell(next.versionId(), "UNRESOLVED", "多处变化，请核对原文",
              matches.stream().flatMap(m -> m.newValue().sources().stream()).toList(),
              item.sources(), List.of(), "MULTIPLE_CHANGES");
        } else nextCell = new Cell(next.versionId(), "NOT_DETECTED", "未识别到该项变更",
            List.of(), List.of(), List.of(), null);
        String itemLabel = item.candidateCategories().size() > 1
            ? item.itemLabel() + "（候选：" + String.join("、", item.candidateCategories()) + "）" : item.itemLabel();
        rows.add(new Row(item.itemId(), category.key(), item.itemType(), itemLabel, item.itemId(),
            List.of(new Cell(base.versionId(), item.itemType().equals("OTHER_CLAUSE") ? "TEXT_ONLY" : "FACT",
                item.rawText(), item.sources(), List.of(), List.of(), null), nextCell)));
      }
      // BASIC facts belong to each file header; empty table rows would imply cross-file comparison.
      if (category.key().equals("BASIC")) continue;
      for (String itemType : category.itemTypes()) {
        if (base.items().stream().anyMatch(item -> item.categoryKey().equals(category.key())
            && item.itemType().equals(itemType))) continue;
        rows.add(new Row("template:" + category.key() + ":" + itemType, category.key(), itemType,
            label(itemType), null, List.of(
                new Cell(base.versionId(), "NOT_EXTRACTED", "未识别，请核对", List.of(), List.of(), List.of(), null),
                new Cell(next.versionId(), "NOT_DETECTED", "未识别到该项变更", List.of(), List.of(), List.of(), null))));
      }
    }
    for (var change : summary.summaryRows()) {
      if (!usedChanges.contains(change.rowId())) unassociated.add(new Unassociated(next.versionId(),
          "UNRESOLVED", change.description(), change.newValue().sources(), "UNMATCHED_MATRIX_ROW"));
    }
    for (var finding : summary.unresolvedExpressions()) {
      unassociated.add(new Unassociated(finding.expression().versionId(), "UNRESOLVED",
          finding.expression().quote(), List.of(finding.expression()), finding.reasonCode()));
    }
    for (var item : next.items()) {
      if (!usedNextFactIds.contains(item.itemId()) && unassociated.stream().noneMatch(entry ->
          entry.versionId().equals(next.versionId()) && entry.displayText().equals(item.rawText())
              && entry.sources().equals(item.sources()))) unassociated.add(new Unassociated(next.versionId(),
          "TEXT_ONLY", item.rawText(), item.sources(), "UNASSOCIATED_FILE_CONTENT"));
    }
    // Keep the catalog for older consumers, but give every entry a visible matrix row.
    // A blank anchor means that the relationship to A is unknown, not that B is absent.
    Map<String, Integer> duplicateIds = new HashMap<>();
    for (var entry : unassociated) {
      if (!entry.versionId().equals(next.versionId())) continue;
      var source = entry.sources().getFirst();
      var fact = next.items().stream().filter(item -> item.rawText().equals(entry.displayText())
          && item.sources().equals(entry.sources())).findFirst();
      String categoryKey = fact.map(FactItem::categoryKey).orElse("OTHER");
      String itemType = fact.map(FactItem::itemType).orElse("UNRESOLVED_EXPRESSION");
      String itemLabel = fact.map(FactItem::itemLabel).orElse("待关联表达");
      String identity = "unassociated:" + source.versionId() + ":" + source.startUtf16() + ":"
          + source.endUtf16Exclusive() + ":" + entry.reasonCode() + ":" + entry.status();
      int duplicate = duplicateIds.merge(identity, 1, Integer::sum);
      rows.add(new Row(identity + ":" + duplicate, categoryKey, itemType, itemLabel, null,
          List.of(new Cell(base.versionId(), "NO_ANCHOR", "未关联到 A 事项", List.of(), List.of(), List.of(), null),
              new Cell(entry.versionId(), entry.status(), entry.displayText(), entry.sources(),
                  List.of(), List.of(), entry.reasonCode()))));
    }
    return new Matrix(List.of(new Column(base.versionId(), "原合同 A", "BASE", base.metadata()),
        new Column(next.versionId(), relation.equals("SUPPLEMENT") ? "补充协议 B" : "修订版 B",
            relation, next.metadata())), GoodsSaleProfile.CATEGORIES, List.copyOf(rows),
        List.copyOf(unassociated), List.of("灰色仅表示有限规则未识别到变化，不表示条款确认不变。"));
  }

  private boolean matches(FactItem item, ChangeSummaryBuilder.SummaryRow change) {
    if (!item.itemLabel().equals(change.itemLabel())) return false;
    String type = switch (change.kind()) {
      case CONTRACT_AMOUNT -> "TOTAL";
      case PAYMENT -> switch (change.field()) {
        case AMOUNT -> "PAYMENT_AMOUNT";
        case PAYMENT_DEADLINE -> "PAYMENT_DEADLINE";
        default -> "PAYMENT_CONDITION";
      };
      case DELIVERY -> change.field() == com.kreview.kreview.extraction.RuleModel.Field.DELIVERY_DEADLINE
          ? "DELIVERY_DEADLINE" : "TRANSPORT";
      case LIABILITY_TEXT -> "LIABILITY";
    };
    if (!item.itemType().equals(type)) return false;
    return change.oldValue().sources().stream().anyMatch(old -> item.sources().stream().anyMatch(s -> overlaps(s, old)));
  }
  private boolean overlaps(SourceSpan a, SourceSpan b) {
    return a.versionId().equals(b.versionId()) && a.startUtf16() < b.endUtf16Exclusive()
        && b.startUtf16() < a.endUtf16Exclusive();
  }
  private String label(String type) {
    return switch (type) {
      case "PARTIES" -> "当事人"; case "SIGNED_ON" -> "签订日期"; case "EFFECTIVE_ON" -> "约定生效日期";
      case "GOODS_NAME" -> "货物名称"; case "QUANTITY" -> "数量"; case "SPECIFICATION" -> "规格";
      case "QUALITY" -> "质量标准"; case "TOTAL" -> "合同总价"; case "PAYMENT_AMOUNT" -> "付款金额";
      case "PAYMENT_DEADLINE" -> "付款期限"; case "PAYMENT_CONDITION" -> "付款条件";
      case "DELIVERY_DEADLINE" -> "交付期限"; case "PLACE" -> "交货地点";
      case "TRANSPORT" -> "运输安排"; case "RISK_TRANSFER" -> "风险转移";
      case "STANDARD" -> "验收标准"; case "ACCEPTANCE_DEADLINE" -> "验收期限";
      case "OBJECTION" -> "异议期限"; case "WARRANTY" -> "保修";
      case "LIABILITY" -> "责任条款"; case "PENALTY" -> "违约金";
      case "INTEREST" -> "逾期利息"; case "COMPENSATION" -> "赔偿";
      default -> "其他条款";
    };
  }
}
