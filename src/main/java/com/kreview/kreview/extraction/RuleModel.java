package com.kreview.kreview.extraction;

import com.kreview.kreview.document.SourceSpan;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** B1 pure-domain results, not a persisted/API schema or a legal contract state. */
public final class RuleModel {
  private RuleModel() {}
  public enum Relation { REVISION, SUPPLEMENT }
  public enum Kind { CONTRACT_AMOUNT, PAYMENT, DELIVERY, LIABILITY_TEXT }
  public enum Field { AMOUNT, PAYMENT_DEADLINE, DELIVERY_DEADLINE, CONDITION, TEXT }
  public enum FieldStatus { PARSED, UNPARSED, AMBIGUOUS, NOT_STATED }
  public enum Method { EXPLICIT_REFERENCE, EXACT_CLAUSE_ID, EXACT_ITEM_KEY, NONE }
  public enum TargetStatus { UNIQUE, AMBIGUOUS, UNRESOLVED }

  public record Clause(String id, String key, SourceSpan source) {}

  public record Value(String rawText, String normalizedValue, FieldStatus status,
                      List<SourceSpan> sources) {
    public Value {
      Objects.requireNonNull(rawText);
      Objects.requireNonNull(status);
      sources = List.copyOf(sources);
      if (status == FieldStatus.PARSED && normalizedValue == null) {
        throw new IllegalArgumentException("Parsed fields require a normalized value");
      }
      if (status == FieldStatus.NOT_STATED) {
        if (!rawText.isEmpty() || normalizedValue != null || !sources.isEmpty()) {
          throw new IllegalArgumentException("Missing fields cannot carry invented evidence");
        }
      } else if (sources.isEmpty() || sources.stream().anyMatch(s -> s.quote().isEmpty())) {
        throw new IllegalArgumentException("Stated fields require nonempty evidence");
      }
      if (status != FieldStatus.PARSED && normalizedValue != null) {
        throw new IllegalArgumentException("Unparsed fields cannot carry a normalized value");
      }
    }
    public static Value missing() {
      return new Value("", null, FieldStatus.NOT_STATED, List.of());
    }
  }

  public record Obligation(String id, String clauseId, String clauseKey, Kind kind,
                           String itemLabel, SourceSpan source, Map<Field, Value> fields) {
    public Obligation { fields = Map.copyOf(fields); }
    public Value value(Field field) { return fields.getOrDefault(field, Value.missing()); }
  }

  public record Finding(String reasonCode, Method method, TargetStatus targetStatus,
                        SourceSpan expression, List<SourceSpan> referenceEvidence,
                        List<SourceSpan> candidateTargets) {
    public Finding {
      referenceEvidence = List.copyOf(referenceEvidence);
      candidateTargets = List.copyOf(candidateTargets);
    }
  }

  public record Extraction(List<Clause> clauses, List<Obligation> obligations,
                           List<Finding> findings) {
    public Extraction {
      clauses = List.copyOf(clauses);
      obligations = List.copyOf(obligations);
      findings = List.copyOf(findings);
    }
  }

  public record Change(Kind kind, String itemLabel, Field field, Value oldValue, Value newValue,
                       Method method, TargetStatus targetStatus, List<SourceSpan> referenceEvidence,
                       List<SourceSpan> candidateTargets) {
    public Change {
      referenceEvidence = List.copyOf(referenceEvidence);
      candidateTargets = List.copyOf(candidateTargets);
    }
  }

  public record Analysis(String rulesVersion, Relation relation, List<String> orderedVersionIds,
                         List<Change> changes, List<Finding> findings,
                         Extraction baseExtraction, Extraction nextExtraction) {
    public Analysis {
      orderedVersionIds = List.copyOf(orderedVersionIds);
      changes = List.copyOf(changes);
      findings = List.copyOf(findings);
    }
  }
}
