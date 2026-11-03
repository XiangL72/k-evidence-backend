package com.kreview.kreview.document;

import java.util.Objects;

/** Zero-based UTF-16 [start, end). A location is meaningful only in its immutable version. */
public record SourceSpan(String versionId, int startUtf16, int endUtf16Exclusive, String quote) {
  public SourceSpan {
    Objects.requireNonNull(versionId, "versionId");
    Objects.requireNonNull(quote, "quote");
    if (versionId.isBlank() || startUtf16 < 0 || endUtf16Exclusive < startUtf16
        || endUtf16Exclusive - startUtf16 != quote.length()) {
      throw new IllegalArgumentException("Invalid source span");
    }
  }

  public void validateAgainst(SourceDocument document) {
    if (!versionId.equals(document.versionId())
        || !equals(document.span(startUtf16, endUtf16Exclusive))) {
      throw new IllegalArgumentException("Source evidence does not match its version");
    }
  }
}
