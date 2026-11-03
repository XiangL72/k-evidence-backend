package com.kreview.kreview.extraction;

import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.extraction.RuleModel.Clause;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Retains duplicate numbered clauses; normalization is for lookup keys only. */
public final class ClauseSegmenter {
  private static final String NUMBER = "[0-9零〇一二三四五六七八九十百]{1,6}";
  private static final Pattern HEADING = Pattern.compile("^\\h*(?:第(" + NUMBER
      + ")条(?:第(" + NUMBER + ")款)?|第(" + NUMBER
      + ")款|([1-9][0-9]{0,2}(?:\\.[1-9][0-9]{0,2})?)(?:[.．、]|(?=\\h)))");

  public List<Clause> segment(SourceDocument document) {
    List<Clause> result = new ArrayList<>();
    String text = document.text();
    String key = null;
    String article = null;
    int start = 0;
    for (int line = 0; line < text.length();) {
      int newline = text.indexOf('\n', line);
      int end = newline < 0 ? text.length() : newline + 1;
      String content = text.substring(line, end);
      var heading = HEADING.matcher(content);
      if (heading.find()) {
        if (line > start) add(result, document, key, start, line);
        if (heading.group(1) != null) {
          article = ordinal(heading.group(1));
          key = article;
          if (heading.group(2) != null) key = join(article, ordinal(heading.group(2)));
        } else if (heading.group(3) != null) {
          key = join(article, ordinal(heading.group(3)));
        } else {
          key = heading.group(4);
          article = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
        }
        start = line;
      } else if (content.isBlank() && key == null) {
        if (line > start) add(result, document, key, start, line);
        start = end;
        // Split unnumbered paragraphs; a blank line never ends an explicitly numbered clause.
      }
      line = end;
    }
    if (start < text.length()) add(result, document, key, start, text.length());
    return List.copyOf(result);
  }

  private void add(List<Clause> result, SourceDocument doc, String key, int start, int end) {
    if (!doc.text().substring(start, end).isBlank()) {
      result.add(new Clause(doc.versionId() + ":clause:" + start, key, doc.span(start, end)));
    }
  }

  private String join(String article, String sub) {
    return article == null || sub == null ? null : article + "." + sub;
  }

  /** A deliberately bounded ordinal vocabulary: Arabic 1..999, Chinese 1..999. */
  public static String ordinal(String raw) {
    if (raw.matches("[0-9]{1,3}")) {
      int number = Integer.parseInt(raw);
      return number > 0 ? Integer.toString(number) : null;
    }
    String digit = "[一二三四五六七八九]";
    if (!raw.matches("(?:" + digit + "百(?:[零〇]" + digit + "|" + digit
        + "十" + digit + "?)?|" + digit + "?十" + digit + "?|" + digit + ")")) {
      return null;
    }
    int total = 0, currentDigit = 0;
    for (char c : raw.toCharArray()) {
      if (c == '百' || c == '十') {
        total += (currentDigit == 0 ? 1 : currentDigit) * (c == '百' ? 100 : 10);
        currentDigit = 0;
      } else {
        currentDigit = "零一二三四五六七八九".indexOf(c);
        if (c == '〇') currentDigit = 0;
        if (currentDigit < 0) return null;
      }
    }
    int result = total + currentDigit;
    return result > 0 && result < 1000 ? Integer.toString(result) : null;
  }
}
