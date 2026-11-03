package com.kreview.kreview.extraction;

import static com.kreview.kreview.extraction.RuleModel.*;

import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.classification.ClauseCategorizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.regex.Pattern;

/** Small Chinese sale-contract vocabulary. Unsupported prose remains source text, not inferred facts. */
public final class RuleObligationExtractor {
  public static final Pattern PAYMENT_LABEL = Pattern.compile("首付款|进度款|尾款|第[0-9一二三四五六七八九十]{1,4}笔款");
  public static final Pattern BATCH_LABEL = Pattern.compile("第[0-9一二三四五六七八九十]{1,4}批");
  private static final Pattern TOTAL = Pattern.compile("合同总价|合同金额|总金额");
  private static final Pattern STATEMENT = Pattern.compile("[^。；;\\r\\n]+");
  private static final Pattern NEGATED = Pattern.compile("不是|并非|不为|不应|无需|不支付|不得支付");
  private final ClauseSegmenter segmenter = new ClauseSegmenter();
  private final FieldParser parser = new FieldParser();
  private final ClauseCategorizer categorizer = new ClauseCategorizer();

  public Extraction extract(SourceDocument doc) {
    var clauses = segmenter.segment(doc);
    List<Obligation> obligations = new ArrayList<>();
    List<Finding> findings = new ArrayList<>();
    for (Clause clause : clauses) {
      String clauseText = clause.source().quote();
      String firstLine = clauseText.split("\\r?\\n", 2)[0];
      if ((firstLine.contains("违约") || firstLine.contains("责任"))
          && categorizer.classify(firstLine).candidateCategories().size() <= 1) {
        obligations.add(new Obligation(clause.id() + ":liability", clause.id(), clause.key(),
            Kind.LIABILITY_TEXT, "责任条款", clause.source(),
            java.util.Map.of(Field.TEXT, FieldParser.parsed(clause.source(), clauseText))));
        continue;
      }
      var statement = STATEMENT.matcher(doc.text()).region(
          clause.source().startUtf16(), clause.source().endUtf16Exclusive());
      while (statement.find()) {
        String text = statement.group();
        if (text.isBlank()) continue;
        List<int[]> ranges = List.of(new int[]{statement.start(), statement.end()});
        if (labels(PAYMENT_LABEL, text).size() > 1 || labels(BATCH_LABEL, text).size() > 1) {
          var split = splitLabeledSegments(text, statement.start());
          if (split != null) ranges = split;
        }
        for (var range : ranges) {
        int start = range[0], end = range[1];
        String segment = doc.text().substring(start, end);
        if (NEGATED.matcher(segment).find()) {
          findings.add(new Finding("UNSUPPORTED_QUALIFIER", Method.NONE, TargetStatus.UNRESOLVED,
              doc.span(start, end), List.of(), List.of()));
          continue;
        }
        List<String> payments = labels(PAYMENT_LABEL, segment);
        List<String> batches = labels(BATCH_LABEL, segment);
        Kind kind;
        String label;
        if (segment.contains("违约金")) continue;
        if (payments.size() > 1 || batches.size() > 1) {
          findings.add(new Finding("MULTIPLE_ITEMS_IN_STATEMENT", Method.NONE, TargetStatus.AMBIGUOUS,
              doc.span(start, end), List.of(), List.of(clause.source())));
          continue;
        }
        if (TOTAL.matcher(segment).find()) {
          kind = Kind.CONTRACT_AMOUNT; label = "合同总价";
        } else if (!payments.isEmpty()) {
          kind = Kind.PAYMENT; label = payments.getFirst();
        } else if (segment.contains("交付") && !batches.isEmpty()) {
          kind = Kind.DELIVERY; label = batches.getFirst();
        } else if (segment.contains("交付") && (segment.contains("应") || segment.contains("期限"))) {
          kind = Kind.DELIVERY; label = "未命名交付";
        } else if (segment.contains("付款期限") || segment.contains("支付")) {
          kind = Kind.PAYMENT; label = "未命名付款";
        } else continue;

        var fields = new EnumMap<Field, Value>(Field.class);
        if (kind == Kind.CONTRACT_AMOUNT || kind == Kind.PAYMENT) {
          var money = parser.findMoney(doc, start, end);
          if (kind == Kind.CONTRACT_AMOUNT && money.status() == FieldStatus.NOT_STATED) {
            money = FieldParser.literal(doc.span(start, end), FieldStatus.UNPARSED);
          }
          add(fields, Field.AMOUNT, money);
        }
        if (kind == Kind.PAYMENT || kind == Kind.DELIVERY) {
          Field deadline = kind == Kind.PAYMENT ? Field.PAYMENT_DEADLINE : Field.DELIVERY_DEADLINE;
          add(fields, deadline, parser.findDeadline(doc, start, end));
          add(fields, Field.CONDITION, parser.findCondition(doc, start, end));
        }
        if (!fields.isEmpty()) {
          obligations.add(new Obligation(doc.versionId() + ":item:" + start, clause.id(),
              clause.key(), kind, label, doc.span(start, end), fields));
        }
        }
      }
    }
    return new Extraction(clauses, obligations, findings);
  }

  private List<int[]> splitLabeledSegments(String text, int absoluteStart) {
    if (!text.contains("，")) return null;
    List<int[]> ranges = new ArrayList<>();
    int start = 0;
    for (int i = 0; i <= text.length(); i++) {
      if (i != text.length() && text.charAt(i) != '，') continue;
      if (i > start) {
        String part = text.substring(start, i);
        if (labels(PAYMENT_LABEL, part).size() + labels(BATCH_LABEL, part).size() != 1) return null;
        ranges.add(new int[]{absoluteStart + start, absoluteStart + i});
      }
      start = i + 1;
    }
    return ranges.size() > 1 ? ranges : null;
  }

  public static List<String> labels(Pattern pattern, String text) {
    var matcher = pattern.matcher(text);
    List<String> result = new ArrayList<>();
    while (matcher.find()) result.add(matcher.group());
    return result;
  }

  private void add(EnumMap<Field, Value> fields, Field field, Value value) {
    if (value.status() != FieldStatus.NOT_STATED) fields.put(field, value);
  }
}
