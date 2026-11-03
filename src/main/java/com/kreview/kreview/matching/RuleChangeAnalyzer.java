package com.kreview.kreview.matching;

import static com.kreview.kreview.extraction.RuleModel.*;

import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.extraction.FieldParser;
import com.kreview.kreview.extraction.RuleObligationExtractor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** B1 experiment entry point. No Spring, database, UI, LLM, or global obligation identity. */
public final class RuleChangeAnalyzer {
  public static final String RULES_VERSION = "zh-sale-b1-v1";
  private final RuleObligationExtractor extractor = new RuleObligationExtractor();
  private final ExplicitReferenceMatcher references = new ExplicitReferenceMatcher();

  public Analysis analyze(SourceDocument base, SourceDocument next, Relation relation) {
    Objects.requireNonNull(relation, "relation");
    if (base.versionId().equals(next.versionId())) {
      throw new IllegalArgumentException("Two distinct document version IDs are required");
    }
    var before = extractor.extract(base);
    if (relation == Relation.SUPPLEMENT) {
      var result = references.match(next, before);
      var findings = new ArrayList<>(before.findings());
      findings.addAll(result.findings());
      // A supplement's modified fields are not a new complete obligation/version.
      return new Analysis(RULES_VERSION, relation, List.of(base.versionId(), next.versionId()),
          result.changes(), findings, before, new Extraction(List.of(), List.of(), List.of()));
    }
    var after = extractor.extract(next);
    List<Change> changes = new ArrayList<>();
    List<Finding> findings = new ArrayList<>(before.findings());
    findings.addAll(after.findings());
    var handledOldIds = new HashSet<String>();
    for (Obligation item : after.obligations()) {
      boolean numbered = item.clauseKey() != null;
      Method method = numbered ? Method.EXACT_CLAUSE_ID : Method.EXACT_ITEM_KEY;
      var candidates = before.obligations().stream()
          .filter(o -> o.kind() == item.kind() && o.itemLabel().equals(item.itemLabel()))
          .filter(o -> !numbered || item.clauseKey().equals(o.clauseKey())).toList();
      long newCount = after.obligations().stream()
          .filter(o -> o.kind() == item.kind() && o.itemLabel().equals(item.itemLabel()))
          .filter(o -> !numbered || item.clauseKey().equals(o.clauseKey())).count();
      boolean duplicateNumber = numbered && (duplicateClause(before, item.clauseKey())
          || duplicateClause(after, item.clauseKey()));
      boolean genericUnnumbered = !numbered && (item.itemLabel().startsWith("未命名")
          || item.kind() == Kind.LIABILITY_TEXT);
      if (candidates.size() != 1 || newCount != 1 || duplicateNumber || genericUnnumbered) {
        candidates.forEach(o -> handledOldIds.add(o.id()));
        boolean ambiguous = candidates.size() > 1 || newCount > 1 || duplicateNumber;
        findings.add(new Finding(duplicateNumber ? "DUPLICATE_CLAUSE_ID"
            : ambiguous ? "MULTIPLE_TARGET_ITEMS" : "NO_EXACT_ITEM_MATCH",
            genericUnnumbered ? Method.NONE : method,
            ambiguous ? TargetStatus.AMBIGUOUS : TargetStatus.UNRESOLVED,
            item.source(), List.of(), candidates.stream().map(Obligation::source).toList()));
        continue;
      }
      var old = candidates.getFirst();
      handledOldIds.add(old.id());
      for (Field field : Field.values()) {
        Value a = old.value(field), b = item.value(field);
        if (!FieldParser.sameValue(a, b)) {
          changes.add(new Change(item.kind(), item.itemLabel(), field, a, b, method,
              TargetStatus.UNIQUE, List.of(), List.of(old.source())));
        }
      }
    }
    for (Obligation old : before.obligations()) {
      if (!handledOldIds.contains(old.id())) {
        findings.add(new Finding("UNMATCHED_OLD_ITEM", Method.NONE, TargetStatus.UNRESOLVED,
            old.source(), List.of(), List.of()));
      }
    }
    return new Analysis(RULES_VERSION, relation, List.of(base.versionId(), next.versionId()),
        changes, findings, before, after);
  }

  private boolean duplicateClause(Extraction extraction, String key) {
    return extraction.clauses().stream().filter(c -> key.equals(c.key())).count() > 1;
  }
}
