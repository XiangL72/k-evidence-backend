package com.kreview.kreview.extraction;

import static com.kreview.kreview.extraction.RuleModel.*;

import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.document.SourceSpan;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded lexical field parsing. Relative deadlines stay relative; no calendar inference. */
public final class FieldParser {
  private static final Pattern MONEY = Pattern.compile(
      "(?<![0-9.,])(?:人民币|RMB|￥|¥)?[-+]?[0-9][0-9,]*(?:\\.[0-9]+)?(?:万元|元)");
  private static final Pattern DATE = Pattern.compile("([0-9]{4})年([0-9]{1,2})月([0-9]{1,2})日");
  private static final String ANCHOR = "(?:验收合格|验收完成|收到发票|收到货物|合同签署|验收|签署|交付|收货|发货)";
  private static final Pattern RELATIVE = Pattern.compile("(" + ANCHOR
      + ")后([0-9零〇一二三四五六七八九十百]{1,6})(个工作日|个自然日|日|天)");
  private static final Pattern DEADLINE = Pattern.compile(DATE.pattern() + "|" + RELATIVE.pattern());
  private static final Pattern CONDITION = Pattern.compile(ANCHOR + "(?=后)");
  private static final Pattern UNSUPPORTED_MONEY = Pattern.compile("人民币|RMB|￥|¥|[壹贰叁肆伍陆柒捌玖拾佰仟万]+元|[0-9]+(?:美元|欧元)");

  public Value findMoney(SourceDocument doc, int start, int end) {
    String text = doc.text().substring(start, end);
    var matches = matches(doc, start, end, MONEY);
    // Do not silently choose the Arabic number when another stated representation may conflict.
    if (text.matches("(?s).*(?:大写|[壹贰叁肆伍陆柒捌玖拾佰仟]).*")) {
      return literal(doc.span(start, end), FieldStatus.UNPARSED);
    }
    if (matches.size() > 1) return literal(doc.span(start, end), FieldStatus.AMBIGUOUS);
    if (matches.isEmpty()) {
      return UNSUPPORTED_MONEY.matcher(text).find()
          ? literal(doc.span(start, end), FieldStatus.UNPARSED) : Value.missing();
    }
    SourceSpan source = matches.getFirst();
    String raw = source.quote();
    if (!raw.startsWith("人民币") && !raw.startsWith("RMB")) {
      // 元 and ¥ alone do not establish a currency; retain surrounding currency qualifiers.
      return literal(doc.span(start, end), FieldStatus.UNPARSED);
    }
    String number = raw.replaceFirst("^(?:人民币|RMB|￥|¥)", "").replaceFirst("(?:万元|元)$", "");
    if (!number.matches("(?:[0-9]{1,18}|[0-9]{1,3}(?:,[0-9]{3}){1,5})(?:\\.[0-9]{1,2})?")) {
      return literal(source, FieldStatus.UNPARSED);
    }
    var amount = new BigDecimal(number.replace(",", ""));
    if (raw.endsWith("万元")) amount = amount.multiply(BigDecimal.valueOf(10_000));
    return parsed(source, "CNY:" + amount.stripTrailingZeros().toPlainString());
  }

  public Value findDeadline(SourceDocument doc, int start, int end) {
    var matches = matches(doc, start, end, DEADLINE);
    String text = doc.text().substring(start, end);
    if (matches.size() > 1 || (!matches.isEmpty() && text.contains("或"))) {
      return literal(doc.span(start, end), FieldStatus.AMBIGUOUS);
    }
    if (matches.isEmpty()) {
      return text.contains("期限") || text.contains("截止") || text.contains("后")
          ? literal(doc.span(start, end), FieldStatus.UNPARSED) : Value.missing();
    }
    SourceSpan source = matches.getFirst();
    var date = DATE.matcher(source.quote());
    if (date.matches()) {
      try {
        return parsed(source, LocalDate.of(Integer.parseInt(date.group(1)),
            Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3))).toString());
      } catch (DateTimeException ex) {
        return literal(source, FieldStatus.UNPARSED);
      }
    }
    var relative = RELATIVE.matcher(source.quote());
    if (!relative.matches()) return literal(source, FieldStatus.UNPARSED);
    String number = ClauseSegmenter.ordinal(relative.group(2));
    if (number == null) return literal(source, FieldStatus.UNPARSED);
    String unit = relative.group(3).equals("个工作日") ? "WORKING_DAYS" : "DAYS";
    return parsed(source, "RELATIVE:" + relative.group(1) + ":" + number + ":" + unit);
  }

  public Value findCondition(SourceDocument doc, int start, int end) {
    var matches = matches(doc, start, end, CONDITION);
    if (matches.isEmpty()) return Value.missing();
    if (matches.size() > 1) return literal(doc.span(start, end), FieldStatus.AMBIGUOUS);
    return parsed(matches.getFirst(), matches.getFirst().quote());
  }

  public Value parseChangeValue(Field field, SourceDocument doc, int start, int end) {
    Value value = switch (field) {
      case AMOUNT -> findMoney(doc, start, end);
      case PAYMENT_DEADLINE, DELIVERY_DEADLINE -> findDeadline(doc, start, end);
      case CONDITION, TEXT -> parsed(doc.span(start, end), doc.text().substring(start, end));
    };
    if (value.status() == FieldStatus.NOT_STATED) return literal(doc.span(start, end), FieldStatus.UNPARSED);
    if (value.status() == FieldStatus.PARSED && field != Field.CONDITION && field != Field.TEXT) {
      var source = value.sources().getFirst();
      String before = doc.text().substring(start, source.startUtf16());
      String after = doc.text().substring(source.endUtf16Exclusive(), end);
      boolean allowedSuffix = field == Field.AMOUNT ? after.isBlank() : after.strip().matches("[内前]?");
      if (!before.isBlank() || !allowedSuffix) return literal(doc.span(start, end), FieldStatus.UNPARSED);
    }
    return value;
  }

  public static Value parsed(SourceSpan source, String normalized) {
    return new Value(source.quote(), normalized, FieldStatus.PARSED, List.of(source));
  }

  public static Value literal(SourceSpan source, FieldStatus status) {
    return new Value(source.quote(), null, status, List.of(source));
  }

  public static boolean sameValue(Value a, Value b) {
    if (a.status() == FieldStatus.PARSED && b.status() == FieldStatus.PARSED) {
      return a.normalizedValue().equals(b.normalizedValue());
    }
    return a.status() == b.status() && a.rawText().equals(b.rawText());
  }

  private List<SourceSpan> matches(SourceDocument doc, int start, int end, Pattern pattern) {
    List<SourceSpan> result = new ArrayList<>();
    var matcher = pattern.matcher(doc.text()).region(start, end);
    while (matcher.find()) result.add(doc.span(matcher.start(), matcher.end()));
    return result;
  }
}
