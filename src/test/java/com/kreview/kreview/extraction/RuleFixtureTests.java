package com.kreview.kreview.extraction;

import static org.junit.jupiter.api.Assertions.*;
import static com.kreview.kreview.extraction.RuleModel.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kreview.kreview.document.SourceDocument;
import com.kreview.kreview.document.SourceSpan;
import com.kreview.kreview.matching.RuleChangeAnalyzer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleFixtureTests {
  private final ObjectMapper mapper = new ObjectMapper();

  @ParameterizedTest(name = "{0}: synthetic rule acceptance")
  @ValueSource(strings = {"F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11", "F12"})
  void matchesIndependentlySpecifiedGoldenFieldsAndEvidence(String id) throws Exception {
    var base = new SourceDocument("v1", resource(id, "base.txt"));
    var next = new SourceDocument("v2", resource(id, "next.txt"));
    JsonNode metadata = mapper.readTree(resource(id, "metadata.json"));
    JsonNode expected = mapper.readTree(resource(id, "expected.json"));
    assertTrue(metadata.get("b1RuleCase").asBoolean(), "Deferred integration cases are not B1 passes");
    var result = new RuleChangeAnalyzer().analyze(base, next, Relation.valueOf(metadata.get("relation").asText()));
    ObjectNode actual = mapper.createObjectNode();
    ArrayNode changes = actual.putArray("changes");
    for (var change : result.changes()) {
      var row = changes.addObject();
      row.put("kind", change.kind().name());
      row.put("itemLabel", change.itemLabel());
      row.put("field", change.field().name());
      row.put("method", change.method().name());
      row.put("targetStatus", change.targetStatus().name());
      row.set("oldSource", mapper.valueToTree(change.oldValue().sources().getFirst()));
      row.set("newSource", mapper.valueToTree(change.newValue().sources().getFirst()));
    }
    ArrayNode findings = actual.putArray("findings");
    for (var finding : result.findings()) {
      findings.addObject().put("reasonCode", finding.reasonCode())
          .put("targetStatus", finding.targetStatus().name()).put("method", finding.method().name())
          .put("candidateCount", finding.candidateTargets().size());
    }
    Path output = Path.of("target/v1-rule-results", id);
    Files.createDirectories(output);
    mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("actual.json").toFile(), result);
    mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("projection.json").toFile(), actual);
    boolean pass = sorted(expected.get("changes")).equals(sorted(actual.get("changes")))
        && sorted(expected.get("findings")).equals(sorted(actual.get("findings")));
    Files.writeString(output.resolve("review.md"), "# " + id + " " + metadata.get("title").asText()
        + "\n\nGolden projection: " + (pass ? "PASS" : "FAIL")
        + "\n\n变化条数：" + result.changes().size() + "；待解析/歧义条数：" + result.findings().size()
        + "。这是固定开发样例，不是泛化评测。\n\n```json\n"
        + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n```\n");
    assertEquals(sorted(expected.get("changes")), sorted(actual.get("changes")), id + " changes");
    assertEquals(sorted(expected.get("findings")), sorted(actual.get("findings")), id + " findings");
    assertEquals(List.of("v1", "v2"), result.orderedVersionIds());
    validateEverySpan(mapper.valueToTree(result), Map.of("v1", base, "v2", next));
    if (id.equals("F04")) {
      assertEquals(new SourceSpan("v2", 5, 14, "原合同第三条第2款"), result.changes().getFirst().referenceEvidence().getFirst());
      assertTrue(result.nextExtraction().obligations().isEmpty(), "No fabricated full obligation from a supplement");
    }
    if (id.equals("F11")) {
      var relative = result.changes().stream().filter(c -> c.field() == Field.PAYMENT_DEADLINE).findFirst().orElseThrow();
      assertEquals("RELATIVE:验收:15:DAYS", relative.newValue().normalizedValue());
    }
  }

  private List<JsonNode> sorted(JsonNode array) {
    var result = new ArrayList<JsonNode>();
    array.forEach(result::add);
    result.sort(Comparator.comparing(JsonNode::toString));
    return result;
  }

  private void validateEverySpan(JsonNode node, Map<String, SourceDocument> documents) throws Exception {
    if (node.isObject() && node.has("startUtf16")) {
      var span = mapper.treeToValue(node, SourceSpan.class);
      assertNotNull(documents.get(span.versionId()));
      span.validateAgainst(documents.get(span.versionId()));
    }
    if (node.isContainerNode()) for (JsonNode child : node) validateEverySpan(child, documents);
  }

  private String resource(String id, String file) throws Exception {
    try (var input = getClass().getResourceAsStream("/v1/" + id + "/" + file)) {
      assertNotNull(input);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
