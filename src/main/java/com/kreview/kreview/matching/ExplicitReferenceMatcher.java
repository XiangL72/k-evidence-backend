package com.kreview.kreview.matching;

import static com.kreview.kreview.extraction.RuleModel.*;

import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.document.SourceSpan;
import com.kreview.kreview.extraction.ClauseSegmenter;
import com.kreview.kreview.extraction.FieldParser;
import com.kreview.kreview.extraction.RuleObligationExtractor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Matches only an explicit reference in the same sentence, with a verified old-value quote. */
public final class ExplicitReferenceMatcher {
  private static final String NUMBER = "[0-9零〇一二三四五六七八九十百]{1,6}";
  private static final Pattern REFERENCE = Pattern.compile("原合同第(" + NUMBER + ")条(?:第(" + NUMBER + ")款)?");
  private static final Pattern SENTENCE = Pattern.compile("[^。；;\\r\\n]+");
  // Commas inside Arabic amounts are part of the value; prose commas delimit operations.
  private static final String TOKEN = "(?:[^，,；;。\\r\\n]|(?<=[0-9]),(?=[0-9]))";
  private static final Pattern OPERATION = Pattern.compile("由(" + TOKEN + "{1,120}?)(?:修改为|变更为|调整为|改为)(" + TOKEN + "{1,120})");
  private static final Pattern INCREASE_TO = Pattern.compile("(?:合同总价|合同金额|总金额)增加至((?:人民币|RMB)[0-9][0-9,]*(?:\\.[0-9]{1,2})?(?:万元|元))");
  private static final Pattern QUOTED_OLD = Pattern.compile("(?:由|从)((?:人民币|RMB)[0-9][0-9,]*(?:\\.[0-9]{1,2})?(?:万元|元))");
  private static final Pattern HINT = Pattern.compile("合同总价|合同金额|总金额|付款条件|交付条件|付款期限|交付期限|金额|责任条款");
  private static final Pattern QUALIFIER = Pattern.compile("不同意|不得|不将|不予|暂不|尚未|建议|拟|是否|不修改|不变更|不调整|如果|假如|若");
  private final FieldParser parser = new FieldParser();

  public record Result(List<Change> changes, List<Finding> findings) {
    public Result { changes = List.copyOf(changes); findings = List.copyOf(findings); }
  }

  private record ChangeKey(String versionId, int start, int end, Field field) {}

  public Result match(SourceDocument supplement, Extraction base) {
    List<Change> changes = new ArrayList<>();
    List<Finding> findings = new ArrayList<>();
    var sentence = SENTENCE.matcher(supplement.text());
    while (sentence.find()) {
      String text = sentence.group();
      if (text.isBlank()) continue;
      var expression = supplement.span(sentence.start(), sentence.end());
      List<SourceSpan> refs = new ArrayList<>();
      List<String> keys = new ArrayList<>();
      var reference = REFERENCE.matcher(text);
      while (reference.find()) {
        refs.add(supplement.span(sentence.start() + reference.start(), sentence.start() + reference.end()));
        String key = ClauseSegmenter.ordinal(reference.group(1));
        if (reference.group(2) != null) {
          String sub = ClauseSegmenter.ordinal(reference.group(2));
          key = key == null || sub == null ? null : key + "." + sub;
        }
        keys.add(key);
      }
      if (refs.isEmpty()) {
        if (text.contains("改") || text.contains("调整") || text.contains("变更") || text.contains("增加") || text.contains("分两批")) {
          findings.add(finding("NO_EXPLICIT_REFERENCE", Method.NONE, TargetStatus.UNRESOLVED,
              expression, refs, List.of()));
        }
        continue;
      }
      if (refs.size() != 1 || keys.getFirst() == null) {
        findings.add(finding(refs.size() > 1 ? "MULTIPLE_REFERENCES" : "INVALID_REFERENCE",
            Method.EXPLICIT_REFERENCE, refs.size() > 1 ? TargetStatus.AMBIGUOUS : TargetStatus.UNRESOLVED,
            expression, refs, List.of()));
        continue;
      }
      if (QUALIFIER.matcher(text).find()) {
        findings.add(finding("UNSUPPORTED_QUALIFIER", Method.EXPLICIT_REFERENCE, TargetStatus.UNRESOLVED,
            expression, refs, List.of()));
        continue;
      }
      String key = keys.getFirst();
      var scope = base.clauses().stream().filter(c -> key.equals(c.key())
          || (!key.contains(".") && c.key() != null && c.key().startsWith(key + "."))).toList();
      var candidates = scope.stream().map(Clause::source).toList();
      if (scope.isEmpty()) {
        findings.add(finding("REFERENCE_NOT_FOUND", Method.EXPLICIT_REFERENCE, TargetStatus.UNRESOLVED,
            expression, refs, candidates));
        continue;
      }
      // A duplicated parent article is ambiguous even if only one matching subclause has a field.
      String article = key.split("\\.")[0];
      long parentCount = base.clauses().stream().filter(c -> article.equals(c.key())).count();
      if (scope.stream().filter(c -> key.equals(c.key())).count() > 1 || parentCount > 1) {
        findings.add(finding("DUPLICATE_REFERENCE", Method.EXPLICIT_REFERENCE, TargetStatus.AMBIGUOUS,
            expression, refs, candidates));
        continue;
      }
      if (text.matches(".*(?:分[为成]?两批|拆分|合并).*")) {
        findings.add(finding("COMPLEX_SPLIT", Method.EXPLICIT_REFERENCE, TargetStatus.UNRESOLVED,
            expression, refs, candidates));
        continue;
      }
      var operations = OPERATION.matcher(text);
      int operationCount = 0;
      var increase = INCREASE_TO.matcher(text);
      while (increase.find()) {
        operationCount++;
        if (increase.start() < refs.getFirst().endUtf16Exclusive() - sentence.start()) {
          findings.add(finding("CHANGE_BEFORE_REFERENCE", Method.EXPLICIT_REFERENCE,
              TargetStatus.UNRESOLVED, expression, refs, candidates));
          continue;
        }
        String beforeIncrease = text.substring(refs.getFirst().endUtf16Exclusive() - sentence.start(), increase.start());
        var scopeIds = scope.stream().map(Clause::id).toList();
        var targets = base.obligations().stream().filter(o -> scopeIds.contains(o.clauseId()))
            .filter(o -> o.kind() == Kind.CONTRACT_AMOUNT).toList();
        if (targets.size() != 1) {
          findings.add(finding(targets.isEmpty() ? "TARGET_ITEM_NOT_FOUND" : "MULTIPLE_TARGET_ITEMS",
              Method.EXPLICIT_REFERENCE, targets.isEmpty() ? TargetStatus.UNRESOLVED : TargetStatus.AMBIGUOUS,
              expression, refs, targets.stream().map(Obligation::source).toList()));
          continue;
        }
        var target = targets.getFirst();
        Value old = target.value(Field.AMOUNT);
        var quotedOld = QUOTED_OLD.matcher(beforeIncrease);
        if (quotedOld.find()) {
          Value quoted = parser.parseChangeValue(Field.AMOUNT, supplement,
              sentence.start() + refs.getFirst().endUtf16Exclusive() - sentence.start() + quotedOld.start(1),
              sentence.start() + refs.getFirst().endUtf16Exclusive() - sentence.start() + quotedOld.end(1));
          if (!FieldParser.sameValue(old, quoted)) {
            findings.add(finding("OLD_VALUE_MISMATCH", Method.EXPLICIT_REFERENCE,
                TargetStatus.UNRESOLVED, expression, refs, List.of(target.source())));
            continue;
          }
        } else if (beforeIncrease.contains("由") || beforeIncrease.contains("从")) {
          findings.add(finding("UNSUPPORTED_CHANGE_EXPRESSION", Method.EXPLICIT_REFERENCE,
              TargetStatus.UNRESOLVED, expression, refs, candidates));
          continue;
        }
        Value next = parser.parseChangeValue(Field.AMOUNT, supplement,
            sentence.start() + increase.start(1), sentence.start() + increase.end(1));
        if (old.status() != FieldStatus.PARSED || next.status() != FieldStatus.PARSED) {
          findings.add(finding("UNSUPPORTED_VALUE", Method.EXPLICIT_REFERENCE, TargetStatus.UNRESOLVED,
              expression, refs, List.of(target.source())));
          continue;
        }
        try {
          var before = new java.math.BigDecimal(old.normalizedValue().replaceFirst("^CNY:", ""));
          var after = new java.math.BigDecimal(next.normalizedValue().replaceFirst("^CNY:", ""));
          if (after.compareTo(before) <= 0) {
            findings.add(finding("INCREASE_DIRECTION_MISMATCH", Method.EXPLICIT_REFERENCE,
                TargetStatus.UNRESOLVED, expression, refs, List.of(target.source())));
            continue;
          }
        } catch (NumberFormatException ex) {
          findings.add(finding("UNSUPPORTED_VALUE", Method.EXPLICIT_REFERENCE, TargetStatus.UNRESOLVED,
              expression, refs, List.of(target.source())));
          continue;
        }
        changes.add(new Change(Kind.CONTRACT_AMOUNT, target.itemLabel(), Field.AMOUNT, old, next,
            Method.EXPLICIT_REFERENCE, TargetStatus.UNIQUE, refs, List.of(target.source())));
      }
      while (operations.find()) {
        operationCount++;
        String prefix = text.substring(0, operations.start());
        String hint = lastHint(prefix);
        Field field = field(hint);
        Kind kind = kind(hint, prefix);
        List<String> labels = RuleObligationExtractor.labels(
            kind == Kind.DELIVERY ? RuleObligationExtractor.BATCH_LABEL : RuleObligationExtractor.PAYMENT_LABEL,
            prefix);
        if (labels.stream().distinct().count() > 1) {
          findings.add(finding("MULTIPLE_ITEM_LABELS", Method.EXPLICIT_REFERENCE, TargetStatus.AMBIGUOUS,
              expression, refs, candidates));
          continue;
        }
        String label = labels.isEmpty() ? null : labels.getLast();
        if (field == null && kind == Kind.PAYMENT && label != null
            && operations.group(1).contains("人民币") && operations.group(2).contains("人民币")) {
          field = Field.AMOUNT;
        }
        var scopeIds = scope.stream().map(Clause::id).toList();
        var targets = base.obligations().stream().filter(o -> scopeIds.contains(o.clauseId()))
            .filter(o -> kind == null || kind == o.kind())
            .filter(o -> label == null || label.equals(o.itemLabel())).toList();
        if (field == null || kind == null) {
          findings.add(finding("UNSUPPORTED_FIELD", Method.EXPLICIT_REFERENCE, TargetStatus.UNRESOLVED,
              expression, refs, candidates));
          continue;
        }
        if (targets.size() != 1) {
          findings.add(finding(targets.isEmpty() ? "TARGET_ITEM_NOT_FOUND" : "MULTIPLE_TARGET_ITEMS",
              Method.EXPLICIT_REFERENCE, targets.size() > 1 ? TargetStatus.AMBIGUOUS : TargetStatus.UNRESOLVED,
              expression, refs, targets.stream().map(Obligation::source).toList()));
          continue;
        }
        var target = targets.getFirst();
        Value old = target.value(field);
        Value quotedOld = parser.parseChangeValue(field, supplement,
            sentence.start() + operations.start(1), sentence.start() + operations.end(1));
        Value next = parser.parseChangeValue(field, supplement,
            sentence.start() + operations.start(2), sentence.start() + operations.end(2));
        if (old.status() == FieldStatus.NOT_STATED || !FieldParser.sameValue(old, quotedOld)) {
          findings.add(finding(old.status() == FieldStatus.NOT_STATED ? "OLD_FIELD_NOT_STATED" : "OLD_VALUE_MISMATCH",
              Method.EXPLICIT_REFERENCE, TargetStatus.UNRESOLVED, expression, refs, List.of(target.source())));
          continue;
        }
        if (old.status() != FieldStatus.PARSED || next.status() != FieldStatus.PARSED) {
          findings.add(finding("UNSUPPORTED_VALUE", Method.EXPLICIT_REFERENCE, TargetStatus.UNRESOLVED,
              expression, refs, List.of(target.source())));
          continue;
        }
        if (!FieldParser.sameValue(old, next)) {
          changes.add(new Change(target.kind(), target.itemLabel(), field, old, next,
              Method.EXPLICIT_REFERENCE, TargetStatus.UNIQUE, refs, List.of(target.source())));
        }
      }
      if (operationCount == 0) {
        findings.add(finding("UNSUPPORTED_CHANGE_EXPRESSION", Method.EXPLICIT_REFERENCE,
            TargetStatus.UNRESOLVED, expression, refs, candidates));
      }
    }
    // Conflicting expressions within this one supplement are not a single resolved new field.
    var grouped = new LinkedHashMap<ChangeKey, List<Change>>();
    for (var change : changes) {
      var target = change.candidateTargets().getFirst();
      var identity = new ChangeKey(target.versionId(), target.startUtf16(), target.endUtf16Exclusive(), change.field());
      grouped.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(change);
    }
    List<Change> consistent = new ArrayList<>();
    for (var group : grouped.values()) {
      boolean conflict = group.stream().anyMatch(c -> !FieldParser.sameValue(
          c.newValue(), group.getFirst().newValue()));
      if (conflict) {
        for (var change : group) {
          findings.add(finding("CONFLICTING_CHANGES", Method.EXPLICIT_REFERENCE, TargetStatus.AMBIGUOUS,
              change.newValue().sources().getFirst(), change.referenceEvidence(), change.candidateTargets()));
        }
      } else consistent.addAll(group);
    }
    return new Result(consistent, findings);
  }

  private String lastHint(String prefix) {
    var hints = HINT.matcher(prefix);
    String result = "";
    while (hints.find()) result = hints.group();
    return result;
  }

  private Field field(String hint) {
    return switch (hint) {
      case "合同总价", "合同金额", "总金额", "金额" -> Field.AMOUNT;
      case "付款期限" -> Field.PAYMENT_DEADLINE;
      case "交付期限" -> Field.DELIVERY_DEADLINE;
      case "付款条件", "交付条件" -> Field.CONDITION;
      case "责任条款" -> Field.TEXT;
      default -> null;
    };
  }

  private Kind kind(String hint, String prefix) {
    if (hint.startsWith("交付")) return Kind.DELIVERY;
    if (hint.startsWith("付款") || !RuleObligationExtractor.labels(RuleObligationExtractor.PAYMENT_LABEL, prefix).isEmpty()) {
      return Kind.PAYMENT;
    }
    if (hint.equals("责任条款")) return Kind.LIABILITY_TEXT;
    if (hint.equals("合同总价") || hint.equals("合同金额") || hint.equals("总金额")) return Kind.CONTRACT_AMOUNT;
    return null;
  }

  private Finding finding(String reason, Method method, TargetStatus status, SourceSpan expression,
                          List<SourceSpan> refs, List<SourceSpan> targets) {
    return new Finding(reason, method, status, expression, refs, targets);
  }
}
