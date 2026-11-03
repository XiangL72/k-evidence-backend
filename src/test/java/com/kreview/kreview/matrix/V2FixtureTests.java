package com.kreview.kreview.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kreview.kreview.analysis.ChangeSummaryBuilder;
import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.extraction.DocumentFactsExtractor;
import com.kreview.kreview.extraction.RuleModel.Relation;
import com.kreview.kreview.extraction.RuleObligationExtractor;
import com.kreview.kreview.matching.RuleChangeAnalyzer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Expected observations are authored beside the source texts, not copied from engine output. */
class V2FixtureTests {
  private final ObjectMapper mapper = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {"A01", "A02", "A03", "A04", "A05", "A06", "A07", "A08", "A09", "A10"})
  void matrixMatchesIndependentV2Expectations(String id) throws Exception {
    var expected = mapper.readTree(resource(id, "expected.json"));
    var base = new SourceDocument("a", resource(id, "base.txt"));
    var next = new SourceDocument("b", resource(id, "next.txt"));
    var relation = Relation.valueOf(expected.get("relation").asText());
    var analyzed = new RuleChangeAnalyzer().analyze(base, next, relation);
    var extractor = new DocumentFactsExtractor();
    var baseFacts = extractor.extract(base, analyzed.baseExtraction());
    var nextFacts = extractor.extract(next, new RuleObligationExtractor().extract(next));
    var summary = new ChangeSummaryBuilder().build(analyzed, base, next);
    var matrix = new ComparisonMatrixBuilder().build(relation.name(), baseFacts, nextFacts, summary);

    if (expected.has("itemType")) {
      var rows = matrix.rows().stream().filter(row -> expected.get("itemType").asText().equals(row.itemType())
          && row.anchorItemId() != null).toList();
      assertThat(rows).as(id + " anchored rows").isNotEmpty();
      if (expected.has("rowCount")) assertThat(rows).hasSize(expected.get("rowCount").asInt());
      if (expected.has("status")) assertThat(rows).allMatch(row ->
          expected.get("status").asText().equals(row.cells().get(1).status()));
      if (expected.has("changedMin")) assertThat(rows.stream().filter(row ->
          row.cells().get(1).status().equals("CHANGED")).count()).isGreaterThanOrEqualTo(expected.get("changedMin").asLong());
      if (expected.has("baseQuote")) assertThat(rows).anyMatch(row ->
          row.cells().getFirst().sources().stream().anyMatch(s -> expected.get("baseQuote").asText().equals(s.quote())));
      if (expected.has("newQuote")) assertThat(rows).anyMatch(row ->
          row.cells().get(1).sources().stream().anyMatch(s -> expected.get("newQuote").asText().equals(s.quote())));
    }
    if (expected.has("unassociatedMin")) assertThat(matrix.unassociated().size())
        .isGreaterThanOrEqualTo(expected.get("unassociatedMin").asInt());
    if (expected.has("metadataType")) {
      var items = baseFacts.metadata().stream().filter(item ->
          item.itemType().equals(expected.get("metadataType").asText())).toList();
      assertThat(items).hasSize(expected.get("metadataCount").asInt())
          .allMatch(item -> item.parseStatus().equals(expected.get("metadataStatus").asText()));
    }
    if (expected.has("requiredItemTypes")) for (var type : expected.get("requiredItemTypes"))
      assertThat(baseFacts.items()).anyMatch(item -> item.itemType().equals(type.asText()));
    if (expected.has("itemCounts")) {
      var fields = expected.get("itemCounts").fields();
      while (fields.hasNext()) {
        var count = fields.next();
        assertThat(baseFacts.items().stream().filter(item -> item.itemType().equals(count.getKey())).count())
            .as(id + " " + count.getKey()).isEqualTo(count.getValue().asLong());
      }
    }
    if (expected.has("candidateCategories")) {
      var expectedCategories = expected.get("candidateCategories");
      assertThat(baseFacts.items()).anyMatch(item -> item.categoryKey().equals("OTHER")
          && item.candidateCategories().size() == expectedCategories.size()
          && item.candidateCategories().get(0).equals(expectedCategories.get(0).asText())
          && item.candidateCategories().get(1).equals(expectedCategories.get(1).asText()));
    }
    for (var facts : java.util.List.of(baseFacts, nextFacts)) for (var item : facts.items())
      for (var span : item.sources()) span.validateAgainst(facts.versionId().equals("a") ? base : next);
  }

  private String resource(String id, String file) throws Exception {
    try (var stream = getClass().getResourceAsStream("/fixtures/v2/" + id + "/" + file)) {
      assertThat(stream).as(id + "/" + file).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
