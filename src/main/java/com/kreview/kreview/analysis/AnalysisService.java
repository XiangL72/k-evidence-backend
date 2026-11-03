package com.kreview.kreview.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kreview.kreview.comparison.EvidenceDiff;
import com.kreview.kreview.document.DocumentInputException;
import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.document.SourceSpan;
import com.kreview.kreview.document.storage.DocumentSetService;
import com.kreview.kreview.extraction.DocumentFactsExtractor;
import com.kreview.kreview.extraction.RuleModel;
import com.kreview.kreview.matching.RuleChangeAnalyzer;
import com.kreview.kreview.matrix.ComparisonMatrixBuilder;
import com.kreview.kreview.matrix.CompositionMatrixBuilder;
import com.kreview.kreview.matrix.CompositionModel;
import com.kreview.kreview.matrix.MatrixModel;
import com.kreview.kreview.profile.GoodsSaleProfile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalysisService {
  public static final String ALGORITHM_VERSION = "STRICT_MYERS_B3_1";
  public static final int SCHEMA_VERSION = 4;
  public static final String RULES_VERSION = "zh-sale-v2a-2";
  private final Semaphore slot = new Semaphore(1);
  private final DocumentSetService sets;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transaction;
  private final EvidenceDiff diff = new EvidenceDiff();
  private final RuleChangeAnalyzer rules = new RuleChangeAnalyzer();
  private final ChangeSummaryBuilder summaries = new ChangeSummaryBuilder();
  private final DocumentFactsExtractor facts = new DocumentFactsExtractor();
  private final ComparisonMatrixBuilder matrices = new ComparisonMatrixBuilder();
  private final CompositionMatrixBuilder compositionBuilder = new CompositionMatrixBuilder();

  public record Snapshot(UUID analysisId, UUID setId, String relation, List<UUID> orderedVersionIds,
      int schemaVersion, String algorithmVersion, String rulesVersion, Instant createdAt,
      EvidenceDiff.Result diff, ChangeSummaryBuilder.Result extraction, String contractType,
      String categoryProfileVersion, List<DocumentFactsExtractor.DocumentFacts> documentFacts,
      MatrixModel.Matrix matrix, CompositionModel.Matrix composition, List<Evidence> evidenceCatalog) {}
  public record Evidence(String evidenceId, SourceSpan sourceSpan) {}
  public record Summary(UUID analysisId, UUID setId, String relation, String diffStatus,
      String algorithmVersion, Instant createdAt) {}
  public record Listing(List<Summary> items, long total, int offset, int limit) {}

  public AnalysisService(DocumentSetService sets, JdbcTemplate jdbc, ObjectMapper mapper,
                         PlatformTransactionManager manager) {
    this.sets = sets; this.jdbc = jdbc; this.mapper = mapper;
    this.transaction = new TransactionTemplate(manager);
  }

  public Snapshot create(UUID setId) {
    if (!slot.tryAcquire()) throw new DocumentInputException(429, "ANALYSIS_BUSY", "已有分析正在运行，请稍后重试");
    try {
      var detail = sets.get(setId);
      var base = detail.versions().get(0); var next = detail.versions().get(1);
      var oldDoc = new SourceDocument(base.id().toString(), sets.text(setId, base.id()).text());
      var newDoc = new SourceDocument(next.id().toString(), sets.text(setId, next.id()).text());
      var result = diff.analyze(oldDoc, newDoc, next.role());
      validate(result, oldDoc, newDoc);
      var ruleResult = rules.analyze(oldDoc, newDoc, RuleModel.Relation.valueOf(next.role()));
      var structured = summaries.build(ruleResult, oldDoc, newDoc);
      validate(structured, oldDoc, newDoc);
      var baseFacts = facts.extract(oldDoc, ruleResult.baseExtraction());
      // A supplement is not a complete new contract; these are local facts for its own column only.
      var nextFacts = facts.extract(newDoc, new com.kreview.kreview.extraction.RuleObligationExtractor().extract(newDoc));
      boolean supplement = next.role().equals("SUPPLEMENT");
      var matrix = supplement ? null : matrices.build(next.role(), baseFacts, nextFacts, structured);
      var composition = supplement ? compositionBuilder.build(oldDoc, newDoc, next.role()) : null;
      validate(baseFacts, oldDoc, newDoc);
      validate(nextFacts, oldDoc, newDoc);
      if (supplement) validate(composition, oldDoc, newDoc);
      else validate(matrix, oldDoc, newDoc);
      int schemaVersion = supplement ? SCHEMA_VERSION : 3;
      String profileVersion = supplement ? "goods-sale-composition-1" : GoodsSaleProfile.VERSION;
      var snapshot = new Snapshot(UUID.randomUUID(), setId, next.role(), List.of(base.id(), next.id()),
          schemaVersion, ALGORITHM_VERSION, RULES_VERSION,
          Instant.now().truncatedTo(ChronoUnit.MICROS), result, structured,
          GoodsSaleProfile.CONTRACT_TYPE, profileVersion, List.of(baseFacts, nextFacts), matrix,
          composition, supplement ? evidenceCatalog(baseFacts, nextFacts, composition)
              : evidenceCatalog(baseFacts, nextFacts, matrix));
      String json = json(snapshot);
      if (json.getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024) {
        throw new DocumentInputException(422, "RESULT_TOO_LARGE", "分析结果超过 8 MiB 上限");
      }
      String config = json(java.util.Map.of("maxLineWork", com.kreview.kreview.comparison.DiffEngine.MAX_LINE_WORK,
          "maxInlineWork", EvidenceDiff.MAX_INLINE_WORK,
          "maxInlineCodePoints", EvidenceDiff.MAX_INLINE_CODE_POINTS,
          "contractType", GoodsSaleProfile.CONTRACT_TYPE,
          "categoryProfileVersion", profileVersion,
          "rulesVersion", RULES_VERSION,
          "oldLineCount", result.oldLineCount(), "newLineCount", result.newLineCount(),
          "elapsedMs", result.elapsedMs(), "diffStatus", result.status()));
      transaction.executeWithoutResult(status -> jdbc.update("""
          INSERT INTO analysis_run (id,set_id,base_version_id,next_version_id,algorithm_version,rules_version,
              schema_version,config_json,result_json,created_at)
          VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)
          """, snapshot.analysisId(), setId, base.id(), next.id(), ALGORITHM_VERSION,
          RULES_VERSION, schemaVersion, config, json, java.sql.Timestamp.from(snapshot.createdAt())));
      return snapshot;
    } finally { slot.release(); }
  }

  public JsonNode get(UUID id) {
    var rows = jdbc.query("SELECT schema_version,result_json::text FROM analysis_run WHERE id=?", (rs, n) ->
        new Stored(rs.getInt(1), rs.getString(2)), id);
    if (rows.isEmpty()) throw new DocumentInputException(404, "NOT_FOUND", "分析结果不存在");
    var row = rows.get(0);
    if (row.schemaVersion != 1 && row.schemaVersion != 2 && row.schemaVersion != 3 && row.schemaVersion != SCHEMA_VERSION)
      throw new DocumentInputException(409, "UNSUPPORTED_SCHEMA", "分析快照版本不受当前程序支持");
    try { return mapper.readTree(row.json); }
    catch (JsonProcessingException ex) { throw new IllegalStateException("Saved analysis JSON is invalid", ex); }
  }

  public Listing list(UUID setId, int offset, int limit) {
    if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 100)
      throw new DocumentInputException(400, "INVALID_PAGINATION", "offset 须为 0–1000000，limit 须为 1–100");
    sets.get(setId);
    long total = jdbc.queryForObject("SELECT count(*) FROM analysis_run WHERE set_id=?", Long.class, setId);
    var items = jdbc.query("""
        SELECT id, set_id, result_json->>'relation', result_json->'diff'->>'status', algorithm_version, created_at
        FROM analysis_run WHERE set_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
        """, (rs, n) -> new Summary((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getString(3),
            rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()), setId, limit, offset);
    return new Listing(items, total, offset, limit);
  }

  private void validate(EvidenceDiff.Result result, SourceDocument oldDoc, SourceDocument newDoc) {
    for (var h : result.hunks()) {
      h.oldSource().validateAgainst(oldDoc); h.newSource().validateAgainst(newDoc);
      for (var i : h.inlineChanges()) { i.oldSource().validateAgainst(oldDoc); i.newSource().validateAgainst(newDoc); }
    }
  }
  private void validate(ChangeSummaryBuilder.Result result, SourceDocument oldDoc, SourceDocument newDoc) {
    for (var row : result.summaryRows()) {
      validate(row.oldValue().sources(), oldDoc, newDoc);
      validate(row.newValue().sources(), oldDoc, newDoc);
      validate(row.referenceEvidence(), oldDoc, newDoc);
      validate(row.candidateTargets(), oldDoc, newDoc);
    }
    for (var date : result.dateEntries()) validate(date.sources(), oldDoc, newDoc);
    for (var finding : result.unresolvedExpressions()) {
      validate(List.of(finding.expression()), oldDoc, newDoc);
      validate(finding.referenceEvidence(), oldDoc, newDoc);
      validate(finding.candidateTargets(), oldDoc, newDoc);
    }
  }
  private void validate(List<com.kreview.kreview.document.SourceSpan> spans,
      SourceDocument oldDoc, SourceDocument newDoc) {
    for (var span : spans) {
      var source = span.versionId().equals(oldDoc.versionId()) ? oldDoc
          : span.versionId().equals(newDoc.versionId()) ? newDoc
          : throwUnknownVersion(span.versionId());
      span.validateAgainst(source);
    }
  }
  private SourceDocument throwUnknownVersion(String versionId) {
    throw new IllegalArgumentException("Source span points to unknown version: " + versionId);
  }
  private void validate(DocumentFactsExtractor.DocumentFacts facts,
      SourceDocument oldDoc, SourceDocument newDoc) {
    for (var item : facts.metadata()) validate(item.sources(), oldDoc, newDoc);
    for (var item : facts.items()) validate(item.sources(), oldDoc, newDoc);
  }
  private void validate(MatrixModel.Matrix matrix, SourceDocument oldDoc, SourceDocument newDoc) {
    for (var row : matrix.rows()) for (var cell : row.cells()) {
      validate(cell.sources(), oldDoc, newDoc);
      validate(cell.oldSources(), oldDoc, newDoc);
      validate(cell.referenceEvidence(), oldDoc, newDoc);
    }
    for (var item : matrix.unassociated()) validate(item.sources(), oldDoc, newDoc);
  }
  private void validate(CompositionModel.Matrix matrix, SourceDocument oldDoc, SourceDocument newDoc) {
    for (var row : matrix.rows()) for (var entry : row.base()) {
      validate(entry.sources(), oldDoc, newDoc); validate(entry.targetSources(), oldDoc, newDoc);
    }
    for (var row : matrix.rows()) for (var entry : row.supplement()) {
      validate(entry.sources(), oldDoc, newDoc); validate(entry.targetSources(), oldDoc, newDoc);
    }
  }
  private List<Evidence> evidenceCatalog(DocumentFactsExtractor.DocumentFacts base,
      DocumentFactsExtractor.DocumentFacts next, MatrixModel.Matrix matrix) {
    var unique = new LinkedHashMap<String, SourceSpan>();
    for (var facts : List.of(base, next)) {
      for (var item : facts.metadata()) addEvidence(unique, item.sources());
      for (var item : facts.items()) addEvidence(unique, item.sources());
    }
    for (var row : matrix.rows()) for (var cell : row.cells()) {
      addEvidence(unique, cell.sources());
      addEvidence(unique, cell.oldSources());
      addEvidence(unique, cell.referenceEvidence());
    }
    for (var item : matrix.unassociated()) addEvidence(unique, item.sources());
    return unique.entrySet().stream().map(e -> new Evidence(e.getKey(), e.getValue())).toList();
  }
  private List<Evidence> evidenceCatalog(DocumentFactsExtractor.DocumentFacts base,
      DocumentFactsExtractor.DocumentFacts next, CompositionModel.Matrix matrix) {
    var unique = new LinkedHashMap<String, SourceSpan>();
    for (var facts : List.of(base, next)) {
      for (var item : facts.metadata()) addEvidence(unique, item.sources());
      for (var item : facts.items()) addEvidence(unique, item.sources());
    }
    for (var row : matrix.rows()) for (var entry : row.base()) {
      addEvidence(unique, entry.sources()); addEvidence(unique, entry.targetSources());
    }
    for (var row : matrix.rows()) for (var entry : row.supplement()) {
      addEvidence(unique, entry.sources()); addEvidence(unique, entry.targetSources());
    }
    return unique.entrySet().stream().map(e -> new Evidence(e.getKey(), e.getValue())).toList();
  }
  private void addEvidence(LinkedHashMap<String, SourceSpan> unique, List<SourceSpan> spans) {
    for (var span : spans) {
      String id = span.versionId() + ":" + span.startUtf16() + ":" + span.endUtf16Exclusive();
      var previous = unique.putIfAbsent(id, span);
      if (previous != null && !previous.equals(span)) throw new IllegalArgumentException("Conflicting evidence identity");
    }
  }
  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot serialize analysis", ex); }
  }
  private record Stored(int schemaVersion, String json) {}
}
