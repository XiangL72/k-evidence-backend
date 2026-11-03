package com.kreview.kreview.document;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kreview.kreview.analysis.AnalysisService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentStorageIT extends PostgresTestSupport {
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired AnalysisService analyses;
  @LocalServerPort int port;

  @Test void migratesEmptyDatabaseAndValidatesSchema() {
    assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class));
    assertEquals(3, jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name in ('document_set','document_version','analysis_run')", Integer.class));
    assertEquals(0, jdbc.queryForObject("select count(*) from analysis_run", Integer.class));
  }

  @Test void savesTwoVersionsAndReopensExactBytesTextHashesAndOrder() throws Exception {
    byte[] first = "\uFEFF第一条 😀 <script>window.bad=true</script>\r\n  尾款  \r\n".getBytes(StandardCharsets.UTF_8);
    byte[] second = "较早的日期：2020年1月1日\n".getBytes(StandardCharsets.UTF_8);
    var result = upload("采购对比", "SUPPLEMENT", "same.txt", first, "same.txt", second);
    assertEquals(201, result.getStatusCode().value(), result.getBody());
    var body = mapper.readTree(result.getBody()); String id = body.get("id").asText();
    assertEquals("BASE", body.at("/versions/0/role").asText());
    assertEquals("SUPPLEMENT", body.at("/versions/1/role").asText());
    assertEquals(body.at("/versions/0/id"), body.at("/versions/1/targetVersionId"));
    assertNotEquals(body.at("/versions/0/id"), body.at("/versions/1/id"));
    var decoded = new Utf8TxtDecoder().decode("same.txt", first);
    String version = body.at("/versions/0/id").asText();
    assertEquals(decoded.rawSha256(), body.at("/versions/0/rawSha256").asText());
    assertEquals(decoded.textSha256(), body.at("/versions/0/textSha256").asText());
    assertArrayEquals(first, jdbc.queryForObject("select raw_bytes from document_version where id=?", byte[].class, UUID.fromString(version)));
    var text = http.getForEntity("/api/document-sets/"+id+"/versions/"+version+"/text", JsonNode.class);
    assertEquals(200, text.getStatusCode().value());
    assertEquals(decoded.text(), text.getBody().get("text").asText());
    assertEquals(body, http.getForObject("/api/document-sets/"+id, JsonNode.class));
    assertFalse(body.has("rawBytes"));
    assertFalse(body.at("/versions/0").has("decodedText"));
  }

  @Test void allowsIdenticalContentAndEmptyDocumentsAsDistinctVersions() throws Exception {
    var response = upload("重复", "REVISION", "a.txt", new byte[0], "a.txt", new byte[0]);
    assertEquals(201, response.getStatusCode().value());
    var body = mapper.readTree(response.getBody());
    assertEquals(body.at("/versions/0/rawSha256"), body.at("/versions/1/rawSha256"));
    assertNotEquals(body.at("/versions/0/id"), body.at("/versions/1/id"));
    assertEquals(0, body.at("/versions/0/lineCount").asInt());
  }

  @Test void rejectsSecondInvalidFileWithoutPartialWrites() {
    int before = count("document_set"), versions = count("document_version");
    for (var invalid : List.of(new byte[]{(byte)0xff}, new byte[]{0}, "a".repeat(100001).getBytes(StandardCharsets.UTF_8), "\n".repeat(5001).getBytes(StandardCharsets.UTF_8))) {
      var response = upload("不能保存", "REVISION", "a.txt", "valid".getBytes(StandardCharsets.UTF_8), "b.txt", invalid);
      assertTrue(response.getStatusCode().is4xxClientError());
      assertFalse(response.getBody().contains("valid"));
    }
    assertEquals(before, count("document_set")); assertEquals(versions, count("document_version"));
  }

  @Test void databaseFailureOnSecondInsertRollsBackSetAndFirstVersion() {
    int before = count("document_set"), versions = count("document_version");
    jdbc.execute("CREATE FUNCTION b2_test_fail_second() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.sequence=2 THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER b2_test_failure BEFORE INSERT ON document_version FOR EACH ROW EXECUTE FUNCTION b2_test_fail_second()");
    try {
      var response = upload("回滚", "REVISION", "a.txt", new byte[0], "b.txt", new byte[0]);
      assertEquals(500, response.getStatusCode().value());
      assertTrue(response.getBody().contains("INTERNAL_ERROR"));
      assertFalse(response.getBody().contains("test failure"));
      assertFalse(response.getBody().contains("SQL"));
      assertEquals(before, count("document_set")); assertEquals(versions, count("document_version"));
    } finally {
      jdbc.execute("DROP TRIGGER b2_test_failure ON document_version");
      jdbc.execute("DROP FUNCTION b2_test_fail_second()");
    }
    assertEquals(201, upload("重试成功", "REVISION", "a.txt", new byte[0], "b.txt", new byte[0]).getStatusCode().value());
  }

  @Test void rejectsCrossSetReadsAndCrossSetForeignKeysAndInvalidSequences() throws Exception {
    var a = mapper.readTree(upload("A", "REVISION", "a.txt", new byte[0], "b.txt", new byte[0]).getBody());
    var b = mapper.readTree(upload("B", "REVISION", "a.txt", new byte[0], "b.txt", new byte[0]).getBody());
    assertEquals(404, http.getForEntity("/api/document-sets/"+a.get("id").asText()+"/versions/"+b.at("/versions/0/id").asText()+"/text", String.class).getStatusCode().value());
    UUID next = UUID.fromString(a.at("/versions/1/id").asText());
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("update document_version set target_version_id=? where id=?", UUID.fromString(b.at("/versions/0/id").asText()), next));
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("update document_version set sequence=3 where id=?", next));
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("update document_version set role='BASE' where id=?", next));
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("update document_version set sequence=1,role='BASE',target_version_id=null where id=?", next));
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("insert into analysis_run(id,set_id,base_version_id,next_version_id,algorithm_version,rules_version,schema_version,config_json,result_json,created_at) values (?,?,?,?,'test','test',1,'{}','{}',now())", UUID.randomUUID(), UUID.fromString(a.get("id").asText()),UUID.fromString(a.at("/versions/0/id").asText()),UUID.fromString(b.at("/versions/1/id").asText())));
  }

  @Test void validatesMultipartMetadataAndLimitsThroughRealHttp() throws Exception {
    var good = parts("{\"displayName\":\"test\",\"relation\":\"REVISION\"}");
    good.add("file1", file("a.txt", new byte[0])); // duplicate
    assertEquals(400, post(good).getStatusCode().value());
    good = parts("{\"displayName\":\"test\",\"relation\":\"REVISION\"}");
    good.add("file3", file("c.txt", new byte[0]));
    assertEquals(400, post(good).getStatusCode().value());
    good = parts("{\"displayName\":\"test\",\"relation\":\"REVISION\"}"); good.remove("file2");
    assertEquals(400, post(good).getStatusCode().value());
    for (String meta : List.of("{}", "{\"displayName\":\"a\",\"relation\":\"REVISION\",\"extra\":1}",
        "{\"displayName\":\"a\",\"relation\":\"REVISION\",\"relation\":\"SUPPLEMENT\"}",
        "{\"displayName\":1,\"relation\":\"REVISION\"}", "{\"displayName\":\"a\",\"relation\":\"OTHER\"}",
        "{\"displayName\":\" \",\"relation\":\"REVISION\"}", "{\"displayName\":\"a\",\"relation\":\"REVISION\"} {}")) {
      assertEquals(400, post(parts(meta)).getStatusCode().value(), meta);
    }
    assertEquals(413, post(parts(" ".repeat(16385))).getStatusCode().value());
    assertEquals(415, upload("pdf", "REVISION", "a.pdf", new byte[0], "b.txt", new byte[0]).getStatusCode().value());
    assertEquals(413, upload("large", "REVISION", "a.txt", new byte[1_048_577], "b.txt", new byte[0]).getStatusCode().value());
    assertError(http.postForEntity("/api/document-sets", new HttpEntity<>("{}", jsonHeaders()), String.class),415,"UNSUPPORTED_CONTENT_TYPE");
    assertError(upload("large", "REVISION", "a.txt", new byte[1_048_577], "b.txt", new byte[0]),413,"UPLOAD_TOO_LARGE");
    var large = parts("{\"displayName\":\"large\",\"relation\":\"REVISION\"}");
    for (int i=0;i<4;i++) large.add("extra"+i,file("x.txt",new byte[900000]));
    assertError(post(large),413,"UPLOAD_TOO_LARGE");
    var wrongType = parts("{}");
    wrongType.set("metadata",new HttpEntity<>("{}"));
    assertError(post(wrongType),415,"UNSUPPORTED_METADATA_TYPE");
  }

  @Test void listPaginationDoesNotIncludeSourceAndRejectsInvalidIdsAndParameters() throws Exception {
    for (int i=0;i<3;i++) assertEquals(201, upload("分页"+i,"REVISION","a.txt",new byte[0],"b.txt",new byte[0]).getStatusCode().value());
    var whole = http.getForObject("/api/document-sets?limit=100", JsonNode.class);
    var page = http.getForObject("/api/document-sets?offset=1&limit=2", JsonNode.class);
    assertEquals(whole.get("total"), page.get("total"));
    assertEquals(whole.at("/items/1/id"), page.at("/items/0/id"));
    assertEquals(whole.at("/items/2/id"), page.at("/items/1/id"));
    assertFalse(page.toString().contains("decodedText"));
    assertFalse(page.toString().contains("rawBytes"));
    for (String suffix : List.of("?limit=0", "?limit=101", "?offset=-1", "?offset=1000001", "?offset=x", "/invalid"))
      assertEquals(400,http.getForEntity("/api/document-sets"+suffix,String.class).getStatusCode().value());
    assertEquals(404,http.getForEntity("/api/document-sets/"+UUID.randomUUID(),String.class).getStatusCode().value());
    assertTrue(http.getForObject("/api/document-sets?offset=1000000",JsonNode.class).get("items").isEmpty());
  }

  @Test void analysisSnapshotPersistsExactEvidenceAndNewRunDoesNotOverwriteOld() throws Exception {
    var group = mapper.readTree(upload("B3快照", "REVISION", "one.txt", "价款😀十元\r\n".getBytes(StandardCharsets.UTF_8),
        "two.txt", "价款😀二十元\n".getBytes(StandardCharsets.UTF_8)).getBody());
    String setId = group.get("id").asText();
    var firstResponse = http.postForEntity("/api/document-sets/"+setId+"/analyses", null, JsonNode.class);
    assertEquals(201, firstResponse.getStatusCode().value(), firstResponse.toString());
    var first = firstResponse.getBody(); String analysisId = first.get("analysisId").asText();
    assertEquals("EXACT", first.at("/diff/status").asText());
    assertEquals("LIMITED_RULES", first.at("/extraction/status").asText());
    assertEquals("价款😀十元\r\n", first.at("/diff/hunks/0/oldSource/quote").asText());
    assertEquals(group.at("/versions/0/id"), first.at("/diff/hunks/0/oldSource/versionId"));
    assertTrue(first.at("/diff/hunks/0/inlineChanges").size() > 0);
    var reopened = http.getForObject("/api/analyses/"+analysisId, JsonNode.class);
    assertEquals(first, reopened);
    var second = http.postForObject("/api/document-sets/"+setId+"/analyses", null, JsonNode.class);
    assertNotEquals(first.get("analysisId"), second.get("analysisId"));
    assertEquals(first, http.getForObject("/api/analyses/"+analysisId, JsonNode.class));
    var listing = http.getForObject("/api/document-sets/"+setId+"/analyses", JsonNode.class);
    assertEquals(2, listing.get("total").asInt());
    assertEquals(2, listing.get("items").size());
    assertFalse(listing.toString().contains("价款"));
  }

  @Test void supplementAnalysisIsNotApplicableAndFailedInsertLeavesNoPartialRun() throws Exception {
    var supplement = mapper.readTree(upload("B3补充", "SUPPLEMENT", "one.txt", "主合同\n".getBytes(StandardCharsets.UTF_8),
        "two.txt", "补充约定\n".getBytes(StandardCharsets.UTF_8)).getBody());
    String setId = supplement.get("id").asText();
    var response = http.postForEntity("/api/document-sets/"+setId+"/analyses", null, JsonNode.class);
    assertEquals(201, response.getStatusCode().value());
    assertEquals("NOT_APPLICABLE", response.getBody().at("/diff/status").asText());
    assertEquals(0, response.getBody().at("/diff/hunks").size());
    int before = count("analysis_run");
    jdbc.execute("CREATE FUNCTION b3_test_fail_analysis() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");
    jdbc.execute("CREATE TRIGGER b3_test_failure BEFORE INSERT ON analysis_run FOR EACH ROW EXECUTE FUNCTION b3_test_fail_analysis()");
    try {
      var failure = http.postForEntity("/api/document-sets/"+setId+"/analyses", null, String.class);
      assertError(failure, 500, "INTERNAL_ERROR");
      assertEquals(before, count("analysis_run"));
    } finally {
      jdbc.execute("DROP TRIGGER b3_test_failure ON analysis_run");
      jdbc.execute("DROP FUNCTION b3_test_fail_analysis()");
    }
    assertEquals(201, http.postForEntity("/api/document-sets/"+setId+"/analyses", null, String.class).getStatusCode().value());
    assertError(http.getForEntity("/api/analyses/invalid", String.class),400,"INVALID_ID");
    assertError(http.getForEntity("/api/analyses/"+UUID.randomUUID(), String.class),404,"NOT_FOUND");
  }

  @Test void finiteRuleSummariesAndUnresolvedExpressionsArePersistedWithEvidence() throws Exception {
    for (String fixture : List.of("F01", "F04", "F06")) {
      String relation = fixture.equals("F01") ? "REVISION" : "SUPPLEMENT";
      var group = mapper.readTree(upload("规则"+fixture, relation, "base.txt", fixture(fixture, "base.txt"),
          "next.txt", fixture(fixture, "next.txt")).getBody());
      var snapshot = http.postForEntity("/api/document-sets/"+group.get("id").asText()+"/analyses", null, JsonNode.class);
      assertEquals(201, snapshot.getStatusCode().value(), snapshot.toString());
      var body = snapshot.getBody();
      assertEquals(relation.equals("SUPPLEMENT") ? 4 : 3, body.get("schemaVersion").asInt());
      assertEquals("GOODS_SALE", body.get("contractType").asText());
      if (relation.equals("SUPPLEMENT")) {
        assertEquals("goods-sale-composition-1", body.get("categoryProfileVersion").asText());
        assertEquals(11, body.at("/composition/rows").size());
      } else {
        assertEquals("goods-sale-1", body.get("categoryProfileVersion").asText());
        assertEquals(2, body.at("/matrix/columns").size());
      }
      assertTrue(body.at("/evidenceCatalog").isArray());
      assertTrue(body.at("/evidenceCatalog").size() > 0);
      for (var evidence : body.get("evidenceCatalog")) {
        String versionId = evidence.at("/sourceSpan/versionId").asText();
        String source = new String(versionId.equals(group.at("/versions/0/id").asText())
            ? fixture(fixture, "base.txt") : fixture(fixture, "next.txt"), java.nio.charset.StandardCharsets.UTF_8);
        int start = evidence.at("/sourceSpan/startUtf16").asInt();
        int end = evidence.at("/sourceSpan/endUtf16Exclusive").asInt();
        assertEquals(source.substring(start, end), evidence.at("/sourceSpan/quote").asText());
        assertEquals(versionId + ":" + start + ":" + end, evidence.get("evidenceId").asText());
      }
      assertEquals("LIMITED_RULES", body.at("/extraction/status").asText());
      if (fixture.equals("F01")) {
        assertEquals("CONTRACT_AMOUNT", body.at("/extraction/summaryRows/0/kind").asText());
        assertEquals("AMOUNT", body.at("/extraction/summaryRows/0/field").asText());
        assertTrue(body.at("/matrix/rows").isArray());
      } else if (fixture.equals("F04")) {
        assertEquals("NOT_APPLICABLE", body.at("/diff/status").asText());
        assertEquals("EXPLICIT_REFERENCE", body.at("/extraction/summaryRows/0/method").asText());
        assertEquals(group.at("/versions/0/id"), body.at("/extraction/summaryRows/0/oldValue/sources/0/versionId"));
        assertEquals(group.at("/versions/1/id"), body.at("/extraction/summaryRows/0/newValue/sources/0/versionId"));
      } else {
        assertEquals(0, body.at("/extraction/summaryRows").size());
        assertEquals("NO_EXPLICIT_REFERENCE", body.at("/extraction/unresolvedExpressions/0/reasonCode").asText());
      }
      assertEquals(body, http.getForObject("/api/analyses/"+body.get("analysisId").asText(), JsonNode.class));
    }
  }

  @Test void schemaOneTwoAndThreeSnapshotsRemainReadableAfterSchemaFourRun() throws Exception {
    var group = mapper.readTree(upload("历史版本", "REVISION", "base.txt", fixture("F01", "base.txt"),
        "next.txt", fixture("F01", "next.txt")).getBody());
    String setId = group.get("id").asText();
    var first = http.postForObject("/api/document-sets/"+setId+"/analyses", null, JsonNode.class);
    UUID id = UUID.fromString(first.get("analysisId").asText());
    var legacy = ((com.fasterxml.jackson.databind.node.ObjectNode) first.deepCopy());
    legacy.put("schemaVersion", 1); legacy.put("rulesVersion", "NOT_APPLIED");
    legacy.set("extraction", mapper.readTree("{\"status\":\"NOT_IMPLEMENTED\"}"));
    jdbc.update("update analysis_run set schema_version=1,rules_version='NOT_APPLIED',result_json=?::jsonb where id=?",
        mapper.writeValueAsString(legacy), id);
    assertEquals(legacy, http.getForObject("/api/analyses/"+id, JsonNode.class));
    var second = http.postForObject("/api/document-sets/"+setId+"/analyses", null, JsonNode.class);
    assertEquals(3, second.get("schemaVersion").asInt());
    assertNotEquals(id.toString(), second.get("analysisId").asText());
    assertEquals(legacy, http.getForObject("/api/analyses/"+id, JsonNode.class));
    var schemaTwo = (com.fasterxml.jackson.databind.node.ObjectNode) second.deepCopy();
    schemaTwo.put("schemaVersion", 2);
    schemaTwo.remove(List.of("contractType", "categoryProfileVersion", "documentFacts", "matrix", "composition", "evidenceCatalog"));
    UUID secondId = UUID.fromString(second.get("analysisId").asText());
    jdbc.update("update analysis_run set schema_version=2,result_json=?::jsonb where id=?",
        mapper.writeValueAsString(schemaTwo), secondId);
    assertEquals(schemaTwo, http.getForObject("/api/analyses/"+secondId, JsonNode.class));
    var third = http.postForObject("/api/document-sets/"+setId+"/analyses", null, JsonNode.class);
    var schemaThree = (com.fasterxml.jackson.databind.node.ObjectNode) third.deepCopy();
    assertEquals(3, schemaThree.get("schemaVersion").asInt());
    UUID thirdId = UUID.fromString(third.get("analysisId").asText());
    assertEquals(schemaThree, http.getForObject("/api/analyses/"+thirdId, JsonNode.class));
  }

  @Test void analysisBusyUnknownSchemaAndInvalidHistoryRequestsHaveStableErrors() throws Exception {
    var group = mapper.readTree(upload("B5错误", "REVISION", "a.txt", new byte[0], "b.txt", new byte[0]).getBody());
    String setId = group.get("id").asText();
    var slot = (java.util.concurrent.Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(analyses, "slot");
    assertNotNull(slot);
    slot.acquireUninterruptibly();
    try { assertError(http.postForEntity("/api/document-sets/"+setId+"/analyses", null, String.class), 429, "ANALYSIS_BUSY"); }
    finally { slot.release(); }
    var saved = http.postForEntity("/api/document-sets/"+setId+"/analyses", null, JsonNode.class);
    assertEquals(201, saved.getStatusCode().value());
    UUID id = UUID.fromString(saved.getBody().get("analysisId").asText());
    jdbc.update("update analysis_run set schema_version=99 where id=?", id);
    assertError(http.getForEntity("/api/analyses/"+id, String.class), 409, "UNSUPPORTED_SCHEMA");
    assertError(http.getForEntity("/api/document-sets/"+setId+"/analyses?limit=0", String.class), 400, "INVALID_PAGINATION");
    assertError(http.getForEntity("/api/document-sets/"+UUID.randomUUID()+"/analyses", String.class), 404, "NOT_FOUND");
  }

  private void assertError(ResponseEntity<String> response, int status, String code) throws Exception {
    assertEquals(status,response.getStatusCode().value(),response.getBody());
    var error=mapper.readTree(response.getBody());
    assertEquals(3,error.size());
    assertEquals(code,error.path("code").asText());
    assertFalse(error.path("message").asText().isBlank());
    assertDoesNotThrow(() -> UUID.fromString(error.path("requestId").asText()));
  }

  private byte[] fixture(String id, String file) throws Exception {
    try (var in = getClass().getResourceAsStream("/v1/"+id+"/"+file)) {
      assertNotNull(in); return in.readAllBytes();
    }
  }

  private int count(String table) { return jdbc.queryForObject("select count(*) from "+table, Integer.class); }
  private ResponseEntity<String> upload(String name, String relation, String name1, byte[] first, String name2, byte[] second) {
    var parts = parts("{\"displayName\":\""+name+"\",\"relation\":\""+relation+"\"}");
    parts.set("file1",file(name1,first)); parts.set("file2",file(name2,second)); return post(parts);
  }
  private LinkedMultiValueMap<String,Object> parts(String metadata) {
    var parts = new LinkedMultiValueMap<String,Object>();
    parts.add("metadata",new HttpEntity<>(metadata,jsonHeaders()));
    parts.add("file1",file("a.txt",new byte[0])); parts.add("file2",file("b.txt",new byte[0])); return parts;
  }
  private HttpHeaders jsonHeaders() { var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);return h; }
  private ByteArrayResource file(String name, byte[] bytes) { return new ByteArrayResource(bytes) { @Override public String getFilename(){return name;} }; }
  private ResponseEntity<String> post(LinkedMultiValueMap<String,Object> parts) {
    var h=new HttpHeaders(); h.setContentType(MediaType.MULTIPART_FORM_DATA);
    return http.postForEntity("/api/document-sets",new HttpEntity<>(parts,h),String.class);
  }
}
