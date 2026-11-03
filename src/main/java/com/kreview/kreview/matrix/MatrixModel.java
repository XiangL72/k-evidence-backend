package com.kreview.kreview.matrix;

import com.kreview.kreview.document.SourceSpan;
import com.kreview.kreview.extraction.DocumentFactsExtractor.FactItem;
import com.kreview.kreview.profile.GoodsSaleProfile.Category;
import java.util.List;

/** Presentation snapshot; no implied consolidated or legally effective contract. */
public final class MatrixModel {
  private MatrixModel() {}
  public record Column(String versionId, String label, String role, List<FactItem> metadata) {}
  public record Cell(String versionId, String status, String displayText,
      List<SourceSpan> sources, List<SourceSpan> oldSources, List<SourceSpan> referenceEvidence,
      String reasonCode) {}
  public record Row(String rowId, String categoryKey, String itemType, String itemLabel,
      String anchorItemId, List<Cell> cells) {}
  public record Unassociated(String versionId, String status, String displayText,
      List<SourceSpan> sources, String reasonCode) {}
  public record Matrix(List<Column> columns, List<Category> categories, List<Row> rows,
      List<Unassociated> unassociated, List<String> warnings) {}
}
