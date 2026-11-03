package com.kreview.kreview.comparison;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.myers.MyersDiff;
import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.document.SourceSpan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded line diff with exact UTF-16 evidence from the saved originals. */
public final class EvidenceDiff {
  public static final int MAX_INLINE_CODE_POINTS = 2_000;
  public static final long MAX_INLINE_WORK = 250_000;
  private final NormalizationService normalizer = new NormalizationService();
  private final DiffEngine engine = new DiffEngine();

  public record InlineChange(ChangeHunk.Type type, SourceSpan oldSource, SourceSpan newSource) {}
  public record Hunk(String changeId, ChangeHunk.Type type, ChangeHunk.LineRange oldRange,
                     ChangeHunk.LineRange newRange, SourceSpan oldSource, SourceSpan newSource,
                     String inlineStatus, List<InlineChange> inlineChanges) {}
  public record Result(String status, String reason, int oldLineCount, int newLineCount,
                       long elapsedMs, List<Hunk> hunks) {}

  public Result analyze(SourceDocument oldDoc, SourceDocument newDoc, String relation) {
    long start = System.nanoTime();
    var oldLines = normalizer.normalize(oldDoc.text()).lines();
    var newLines = normalizer.normalize(newDoc.text()).lines();
    if ("SUPPLEMENT".equals(relation)) return new Result("NOT_APPLICABLE", "SUPPLEMENT_RELATION",
        oldLines.size(), newLines.size(), elapsed(start), List.of());
    if (!"REVISION".equals(relation)) throw new IllegalArgumentException("Unknown relation");
    if (oldLines.stream().map(NormalizationService.SourceLine::comparisonKey).toList().equals(
        newLines.stream().map(NormalizationService.SourceLine::comparisonKey).toList())) {
      return new Result("EXACT", "NONE", oldLines.size(), newLines.size(), elapsed(start), List.of());
    }
    if ((long) oldLines.size() * newLines.size() > DiffEngine.MAX_LINE_WORK) {
      var oldRange = new ChangeHunk.LineRange(0, oldLines.size());
      var newRange = new ChangeHunk.LineRange(0, newLines.size());
      var type = oldLines.isEmpty() ? ChangeHunk.Type.INSERT
          : newLines.isEmpty() ? ChangeHunk.Type.DELETE : ChangeHunk.Type.REPLACE;
      return new Result("BLOCK_FALLBACK", "DIFF_WORK_BUDGET", oldLines.size(), newLines.size(), elapsed(start),
          List.of(new Hunk("chg-0001", type, oldRange, newRange, oldDoc.span(0, oldDoc.text().length()),
              newDoc.span(0, newDoc.text().length()), "NOT_APPLICABLE", List.of())));
    }
    var hunks = new ArrayList<Hunk>();
    for (var change : engine.compare(oldDoc.text(), newDoc.text())) {
      SourceSpan oldSpan = lineSpan(oldDoc, oldLines, change.oldRange());
      SourceSpan newSpan = lineSpan(newDoc, newLines, change.newRange());
      String inlineStatus = "NOT_APPLICABLE";
      List<InlineChange> inline = List.of();
      if (change.type() == ChangeHunk.Type.REPLACE) {
        if (change.oldRange().endLineExclusive() - change.oldRange().startLine() != 1
            || change.newRange().endLineExclusive() - change.newRange().startLine() != 1) {
          inlineStatus = "SKIPPED_MULTILINE";
        } else {
          var oldCp = CodePoints.of(oldSpan.quote()); var newCp = CodePoints.of(newSpan.quote());
          if (oldCp.values.size() > MAX_INLINE_CODE_POINTS || newCp.values.size() > MAX_INLINE_CODE_POINTS
              || (long) oldCp.values.size() * newCp.values.size() > MAX_INLINE_WORK) {
            inlineStatus = "SKIPPED_BUDGET";
          } else {
            inlineStatus = "EXACT";
            inline = inline(oldDoc, newDoc, oldSpan, newSpan, oldCp, newCp);
          }
        }
      }
      hunks.add(new Hunk(change.changeId(), change.type(), change.oldRange(), change.newRange(),
          oldSpan, newSpan, inlineStatus, inline));
    }
    return new Result("EXACT", "NONE", oldLines.size(), newLines.size(), elapsed(start), List.copyOf(hunks));
  }

  private SourceSpan lineSpan(SourceDocument doc, List<NormalizationService.SourceLine> lines,
                              ChangeHunk.LineRange range) {
    int start = range.startLine() == lines.size() ? doc.text().length() : lines.get(range.startLine()).startOffset();
    int end = range.endLineExclusive() == lines.size() ? doc.text().length() : lines.get(range.endLineExclusive()).startOffset();
    return doc.span(start, end);
  }

  private List<InlineChange> inline(SourceDocument oldDoc, SourceDocument newDoc,
      SourceSpan oldSpan, SourceSpan newSpan, CodePoints oldCp, CodePoints newCp) {
    var deltas = new ArrayList<>(DiffUtils.diff(oldCp.values, newCp.values, new MyersDiff<Integer>()).getDeltas());
    deltas.sort(Comparator.comparingInt(d -> d.getSource().getPosition()));
    var result = new ArrayList<InlineChange>();
    for (var d : deltas) {
      var oldSource = oldDoc.span(oldSpan.startUtf16() + oldCp.offsets.get(d.getSource().getPosition()),
          oldSpan.startUtf16() + oldCp.offsets.get(d.getSource().getPosition() + d.getSource().size()));
      var newSource = newDoc.span(newSpan.startUtf16() + newCp.offsets.get(d.getTarget().getPosition()),
          newSpan.startUtf16() + newCp.offsets.get(d.getTarget().getPosition() + d.getTarget().size()));
      var type = switch (d.getType()) {
        case INSERT -> ChangeHunk.Type.INSERT;
        case DELETE -> ChangeHunk.Type.DELETE;
        case CHANGE -> ChangeHunk.Type.REPLACE;
        case EQUAL -> throw new IllegalStateException("Unexpected equal delta");
      };
      result.add(new InlineChange(type, oldSource, newSource));
    }
    return List.copyOf(result);
  }

  private record CodePoints(List<Integer> values, List<Integer> offsets) {
    static CodePoints of(String raw) {
      var values = new ArrayList<Integer>(); var offsets = new ArrayList<Integer>(); offsets.add(0);
      for (int i = 0; i < raw.length();) {
        if (raw.charAt(i) == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
          values.add((int) '\n'); i += 2;
        } else {
          int cp = raw.codePointAt(i); values.add(cp); i += Character.charCount(cp);
        }
        offsets.add(i);
      }
      return new CodePoints(values, offsets);
    }
  }
  private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
