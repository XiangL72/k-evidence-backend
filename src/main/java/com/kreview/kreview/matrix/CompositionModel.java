package com.kreview.kreview.matrix;

import com.kreview.kreview.document.SourceSpan;
import java.util.List;

/** Schema 4: a stable contract composition, with evidence kept per document. */
public final class CompositionModel {
  private CompositionModel() {}
  public record Entry(String clauseKey, String relation, String summary, String note,
      String oldValue, String newValue, List<SourceSpan> sources, List<SourceSpan> targetSources) {}
  public record Row(String key, String category, String label, List<Entry> base, List<Entry> supplement) {}
  public record Matrix(String baseVersionId, String supplementVersionId, List<Row> rows,
      List<String> warnings) {}
}
