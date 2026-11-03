package com.kreview.kreview.analysis;

import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.document.SourceSpan;
import com.kreview.kreview.extraction.RuleModel;
import com.kreview.kreview.extraction.RuleModel.*;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Presents only the finite B1 rule findings; never reconstructs a current contract. */
public final class ChangeSummaryBuilder {
  private static final Pattern ABSOLUTE = Pattern.compile("([0-9]{4})年([0-9]{1,2})月([0-9]{1,2})日");
  private static final Pattern RELATIVE = Pattern.compile("(?:验收合格|验收完成|收到发票|收到货物|合同签署|验收|签署|交付|收货|发货)后[0-9零〇一二三四五六七八九十百]{1,6}(?:个工作日|个自然日|日|天)");

  public record SummaryRow(String rowId, Kind kind, String itemLabel, Field field,
      Value oldValue, Value newValue, Method method, TargetStatus targetStatus,
      List<SourceSpan> referenceEvidence, List<SourceSpan> candidateTargets, String description) {}
  public record DateEntry(String entryId, String versionId, int sequence, String deadlineRole,
      String rawText, String isoDate, String valueKind, List<SourceSpan> sources) {}
  public record Result(String status, List<SummaryRow> summaryRows, List<DateEntry> dateEntries,
      List<Finding> unresolvedExpressions, List<String> warnings) {}

  public Result build(RuleModel.Analysis analysis, SourceDocument base, SourceDocument next) {
    List<SummaryRow> rows = new ArrayList<>();
    for (var change : analysis.changes()) {
      rows.add(new SummaryRow(String.format(java.util.Locale.ROOT, "row-%04d", rows.size() + 1),
          change.kind(), change.itemLabel(), change.field(), change.oldValue(), change.newValue(),
          change.method(), change.targetStatus(), change.referenceEvidence(), change.candidateTargets(),
          description(change)));
    }
    var dates = new ArrayList<DateEntry>();
    collectDates(dates, base, 1, analysis.baseExtraction());
    for (var change : analysis.changes()) {
      if (change.field() != Field.PAYMENT_DEADLINE && change.field() != Field.DELIVERY_DEADLINE) continue;
      if (analysis.relation() == Relation.SUPPLEMENT) addValue(dates, 2, change.field().name(), change.newValue());
    }
    collectDates(dates, next, 2, analysis.nextExtraction());
    // Do not date-sort: first the user-uploaded file order, then original source position.
    dates.sort(Comparator.comparingInt(DateEntry::sequence)
        .thenComparingInt(e -> e.sources().getFirst().startUtf16()));
    var numbered = new ArrayList<DateEntry>();
    for (var date : dates) numbered.add(new DateEntry(String.format(java.util.Locale.ROOT, "date-%04d", numbered.size() + 1),
        date.versionId(), date.sequence(), date.deadlineRole(), date.rawText(), date.isoDate(),
        date.valueKind(), date.sources()));
    return new Result("LIMITED_RULES", List.copyOf(rows), List.copyOf(numbered),
        analysis.findings(), List.of("仅展示有限规则识别的字段；请同时核对全文差异与原文。"));
  }

  private String description(Change c) {
    String subject = switch (c.kind()) {
      case CONTRACT_AMOUNT -> "合同总价";
      case PAYMENT -> c.itemLabel() + "付款";
      case DELIVERY -> c.itemLabel() + "交付";
      case LIABILITY_TEXT -> "责任条款文字";
    };
    String field = switch (c.field()) {
      case AMOUNT -> "金额";
      case PAYMENT_DEADLINE, DELIVERY_DEADLINE -> "期限";
      case CONDITION -> "条件";
      case TEXT -> "文字";
    };
    return subject + field + "：" + label(c.oldValue()) + " → " + label(c.newValue());
  }
  private String label(Value v) { return v.status() == FieldStatus.NOT_STATED ? "未提及" : v.rawText(); }

  private void collectDates(List<DateEntry> out, SourceDocument doc, int sequence, RuleModel.Extraction extracted) {
    for (var obligation : extracted.obligations()) {
      for (var field : List.of(Field.PAYMENT_DEADLINE, Field.DELIVERY_DEADLINE))
        addValue(out, sequence, field.name(), obligation.value(field));
    }
    var absolute = ABSOLUTE.matcher(doc.text());
    while (absolute.find()) {
      var span = doc.span(absolute.start(), absolute.end());
      if (covered(out, span)) continue;
      String before = doc.text().substring(Math.max(0, absolute.start() - 28), absolute.start());
      String after = doc.text().substring(absolute.end(),
          Math.min(doc.text().length(), absolute.end() + 8));
      String role = before.matches("(?s).*(?:签署日期|签订日期|签署于|签订于)[\\h:：]*$")
          ? "SIGNING_DATE"
          : before.matches("(?s).*(?:约定生效日期|生效日期|生效于)[\\h:：]*$")
              || after.matches("(?s)^\\h*起生效.*") ? "EFFECTIVE_DATE" : "OTHER_DATE";
      String iso;
      try { iso = LocalDate.of(Integer.parseInt(absolute.group(1)), Integer.parseInt(absolute.group(2)),
          Integer.parseInt(absolute.group(3))).toString(); }
      catch (DateTimeException ex) { iso = null; }
      out.add(entry(sequence, role, span, iso));
    }
    var relative = RELATIVE.matcher(doc.text());
    while (relative.find()) {
      var span = doc.span(relative.start(), relative.end());
      if (!covered(out, span)) out.add(entry(sequence, "OTHER_DEADLINE", span, null));
    }
  }

  private boolean covered(List<DateEntry> entries, SourceSpan span) {
    return entries.stream().flatMap(e -> e.sources().stream()).anyMatch(s -> s.versionId().equals(span.versionId())
        && s.startUtf16() <= span.startUtf16() && s.endUtf16Exclusive() >= span.endUtf16Exclusive());
  }
  private void addValue(List<DateEntry> out, int sequence, String role, Value value) {
    if (value.status() == FieldStatus.NOT_STATED) return;
    for (var source : value.sources()) {
      if (!covered(out, source)) out.add(entry(sequence, role, source,
          value.normalizedValue() != null && value.normalizedValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
              ? value.normalizedValue() : null));
    }
  }
  private DateEntry entry(int sequence, String role, SourceSpan span, String iso) {
    String kind = iso != null ? "ABSOLUTE" : RELATIVE.matcher(span.quote()).find() ? "RELATIVE" : "UNPARSED";
    return new DateEntry("", span.versionId(), sequence, role, span.quote(), iso, kind, List.of(span));
  }
}
