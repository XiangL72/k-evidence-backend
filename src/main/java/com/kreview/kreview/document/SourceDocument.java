package com.kreview.kreview.document;

import java.util.Objects;

/** Already-decoded, immutable text. Byte decoding and BOM policy belong to TextDocumentDecoder. */
public record SourceDocument(String versionId, String text) {
  public SourceDocument {
    Objects.requireNonNull(versionId, "versionId");
    Objects.requireNonNull(text, "text");
    if (versionId.isBlank()) throw new IllegalArgumentException("versionId must not be blank");
    if (text.length() > 100_000) throw new IllegalArgumentException("Text exceeds UTF-16 limit");
    if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL is not supported");
    long lines = text.chars().filter(c -> c == '\n').count();
    if (!text.isEmpty() && !text.endsWith("\n")) lines++;
    if (lines > 5_000) throw new IllegalArgumentException("Text exceeds line limit");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
          throw new IllegalArgumentException("Unpaired UTF-16 surrogate");
        }
      } else if (Character.isLowSurrogate(c)) {
        throw new IllegalArgumentException("Unpaired UTF-16 surrogate");
      }
    }
  }

  public SourceSpan span(int start, int end) {
    if (start < 0 || end < start || end > text.length() || splitsPair(start) || splitsPair(end)) {
      throw new IllegalArgumentException("Invalid source boundaries");
    }
    return new SourceSpan(versionId, start, end, text.substring(start, end));
  }

  private boolean splitsPair(int offset) {
    return offset > 0 && offset < text.length()
        && Character.isHighSurrogate(text.charAt(offset - 1))
        && Character.isLowSurrogate(text.charAt(offset));
  }
}
