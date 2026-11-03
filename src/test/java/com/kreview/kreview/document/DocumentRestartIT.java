package com.kreview.kreview.document;

import static org.junit.jupiter.api.Assertions.*;
import com.kreview.kreview.KReviewApplication;
import com.kreview.kreview.analysis.AnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.kreview.kreview.document.storage.DocumentSetService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class DocumentRestartIT extends PostgresTestSupport {
  @Test void reopensTheSameSourcesAndHashesAfterApplicationRestart() {
    UUID id;
    DocumentSetService.Detail saved;
    UUID analysisId;
    JsonNode snapshot;
    var decoder = new Utf8TxtDecoder();
    var first = decoder.decode("原合同.txt", "\uFEFF原文😀\r\n".getBytes(StandardCharsets.UTF_8));
    var second = decoder.decode("补充.txt", "补充协议\n".getBytes(StandardCharsets.UTF_8));
    try (var app = start()) {
      saved = app.getBean(DocumentSetService.class).create("重启验收", "SUPPLEMENT", first, second);
      id = saved.id();
      analysisId = app.getBean(AnalysisService.class).create(id).analysisId();
      snapshot = app.getBean(AnalysisService.class).get(analysisId);
    }
    try (var restarted = start()) {
      var service = restarted.getBean(DocumentSetService.class);
      var reopened = service.get(id);
      assertEquals(saved.id(), reopened.id());
      assertEquals(saved.createdAt(), reopened.createdAt());
      assertEquals(saved.displayName(), reopened.displayName());
      assertEquals(saved.versions(), reopened.versions());
      var source = service.text(id, reopened.versions().getFirst().id());
      assertEquals(first.text(), source.text());
      assertEquals(first.textSha256(), source.textSha256());
      assertArrayEquals(first.rawBytes(), restarted.getBean(org.springframework.jdbc.core.JdbcTemplate.class)
          .queryForObject("select raw_bytes from document_version where id=?", byte[].class, source.versionId()));
      assertEquals(snapshot, restarted.getBean(AnalysisService.class).get(analysisId));
      assertEquals("NOT_APPLICABLE", snapshot.at("/diff/status").asText());
    }
  }
  private ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(KReviewApplication.class).run(
        "--server.port=0", "--spring.datasource.url="+POSTGRES.getJdbcUrl(),
        "--spring.datasource.username="+POSTGRES.getUsername(), "--spring.datasource.password="+POSTGRES.getPassword(),
        "--spring.datasource.hikari.maximum-pool-size=2");
  }
}
