package com.kreview.kreview.comparison;

import java.util.ArrayList;
import java.util.List;

/** STRICT comparison keys and source boundaries; no trimming or Unicode normalization. */
public final class NormalizationService {

  public NormalizedText normalize(String text) {
    if (text == null) {
      throw new IllegalArgumentException("Text must not be null");
    }
    List<SourceLine> lines = new ArrayList<>();
    int start = 0;
    for (int offset = 0; offset < text.length(); offset++) {
      if (text.charAt(offset) == '\n') {
        int end = offset + 1;
        lines.add(new SourceLine(start, end, text.substring(start, end).replace("\r\n", "\n")));
        start = end;
      }
    }
    if (start < text.length()) {
      lines.add(new SourceLine(start, text.length(), text.substring(start)));
    }
    return new NormalizedText(text, lines);
  }

  public record SourceLine(int startOffset, int endOffsetExclusive, String comparisonKey) {}

  public record NormalizedText(String originalText, List<SourceLine> lines) {
    public NormalizedText {
      lines = List.copyOf(lines);
    }

    public List<String> comparisonKeys() {
      return lines.stream().map(SourceLine::comparisonKey).toList();
    }

    public String rawSlice(ChangeHunk.LineRange range) {
      if (range.endLineExclusive() > lines.size()) {
        throw new IllegalArgumentException("Line range exceeds input");
      }
      return originalText.substring(boundary(range.startLine()), boundary(range.endLineExclusive()));
    }

    private int boundary(int line) {
      return line == lines.size() ? originalText.length() : lines.get(line).startOffset();
    }
  }
}
