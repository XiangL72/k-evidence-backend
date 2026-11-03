package com.kreview.kreview.extraction;

import com.kreview.kreview.classification.ClauseCategorizer;
import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.document.SourceSpan;
import com.kreview.kreview.extraction.RuleModel.*;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Per-document facts only. An extracted value is not a cross-document change. */
public final class DocumentFactsExtractor {
  public record FactItem(String itemId, String clauseId, String categoryKey, String itemType,
      String itemLabel, String rawText, String normalizedValue, String parseStatus,
      List<SourceSpan> sources, List<String> candidateCategories, String classificationBasis) {}
  public record DocumentFacts(String versionId, List<FactItem> metadata, List<FactItem> items) {}
  private static final String DATE = "([0-9]{4})年([0-9]{1,2})月([0-9]{1,2})日";
  private static final Pattern LABELED = Pattern.compile(
      "(?m)^(?:[\\h]*)(买方|卖方|甲方|乙方|签订日期|签署日期|约定生效日期|生效日期|货物名称|商品名称|数量|规格|质量标准|交货地点)[\\h]*[:：][\\h]*([^\\r\\n]{1,200})");
  private static final Pattern DATE_PATTERN = Pattern.compile(DATE);
  private final ClauseCategorizer categorizer = new ClauseCategorizer();

  public DocumentFacts extract(SourceDocument doc, Extraction obligations) {
    List<FactItem> metadata = new ArrayList<>();
    List<FactItem> items = new ArrayList<>();
    Map<String, Integer> labelCount = new HashMap<>();
    var labeled = LABELED.matcher(doc.text());
    while (labeled.find()) {
      String label = labeled.group(1);
      labelCount.merge(ambiguityKey(label), 1, Integer::sum);
    }
    labeled.reset();
    while (labeled.find()) {
      String label = labeled.group(1);
      String type = switch (label) {
        case "买方", "卖方", "甲方", "乙方" -> "PARTIES";
        case "签订日期", "签署日期" -> "SIGNED_ON";
        case "生效日期", "约定生效日期" -> "EFFECTIVE_ON";
        case "货物名称", "商品名称" -> "GOODS_NAME";
        case "数量" -> "QUANTITY";
        case "规格" -> "SPECIFICATION";
        case "质量标准" -> "QUALITY";
        case "交货地点" -> "PLACE";
        default -> throw new IllegalStateException("Unmapped labeled fact");
      };
      var source = doc.span(labeled.start(2), labeled.end(2));
      String category = type.equals("PARTIES") || type.endsWith("_ON") ? "BASIC"
          : type.equals("PLACE") ? "DELIVERY" : "GOODS";
      String normalized = null;
      String status = "UNPARSED";
      if (type.endsWith("_ON")) {
        var date = DATE_PATTERN.matcher(source.quote());
        if (date.find() && source.quote().substring(date.end()).matches("[。；;\\h]*")) {
          try { normalized = LocalDate.of(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)),
              Integer.parseInt(date.group(3))).toString(); status = "PARSED"; }
          catch (DateTimeException ignored) { /* retain source, not an invented date */ }
        }
      }
      if (labelCount.get(ambiguityKey(label)) > 1) { status = "AMBIGUOUS"; normalized = null; }
      var item = new FactItem(doc.versionId() + ":fact:" + labeled.start(2), clauseAt(obligations, labeled.start(2)),
          category, type, label, source.quote(), normalized, status, List.of(source), List.of(category), "FIELD_LABEL");
      if (category.equals("BASIC")) metadata.add(item); else items.add(item);
    }
    for (var obligation : obligations.obligations()) {
      for (var entry : obligation.fields().entrySet()) {
        var value = entry.getValue();
        if (value.status() == FieldStatus.NOT_STATED) continue;
        String category = switch (obligation.kind()) {
          case CONTRACT_AMOUNT, PAYMENT -> "PRICE_PAYMENT";
          case DELIVERY -> "DELIVERY";
          case LIABILITY_TEXT -> "REMEDIES";
        };
        String type = switch (obligation.kind()) {
          case CONTRACT_AMOUNT -> "TOTAL";
          case LIABILITY_TEXT -> "LIABILITY";
          case PAYMENT -> switch (entry.getKey()) {
            case AMOUNT -> "PAYMENT_AMOUNT";
            case PAYMENT_DEADLINE -> "PAYMENT_DEADLINE";
            default -> "PAYMENT_CONDITION";
          };
          case DELIVERY -> entry.getKey() == Field.DELIVERY_DEADLINE ? "DELIVERY_DEADLINE" : "TRANSPORT";
        };
        items.add(new FactItem(obligation.id() + ":" + entry.getKey(), obligation.clauseId(), category,
            type, obligation.itemLabel(), value.rawText(), value.normalizedValue(), value.status().name(),
            value.sources(), List.of(category), "RULE_OBLIGATION"));
      }
    }
    for (var clause : obligations.clauses()) {
      boolean covered = items.stream().anyMatch(item -> item.clauseId().equals(clause.id()))
          || metadata.stream().anyMatch(item -> item.clauseId().equals(clause.id()));
      if (!covered) {
        var classification = categorizer.classify(clause.source().quote());
        items.add(new FactItem(clause.id() + ":text", clause.id(), classification.categoryKey(), "OTHER_CLAUSE",
            "原文条款", clause.source().quote(), null, "UNPARSED", List.of(clause.source()),
            classification.candidateCategories(), classification.basis()));
      }
    }
    return new DocumentFacts(doc.versionId(), List.copyOf(metadata), List.copyOf(items));
  }
  private String clauseAt(Extraction extraction, int offset) {
    return extraction.clauses().stream().filter(c -> c.source().startUtf16() <= offset
        && offset < c.source().endUtf16Exclusive()).map(Clause::id).findFirst().orElse("");
  }
  private String ambiguityKey(String label) {
    return switch (label) {
      case "签订日期", "签署日期" -> "SIGNED_ON";
      case "生效日期", "约定生效日期" -> "EFFECTIVE_ON";
      case "货物名称", "商品名称" -> "GOODS_NAME";
      default -> label;
    };
  }
}
