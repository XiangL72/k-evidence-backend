package com.kreview.kreview.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.kreview.kreview.analysis.AnalysisService;
import com.kreview.kreview.document.DocumentInputException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AnalysesController {
  private final AnalysisService service;
  public AnalysesController(AnalysisService service) { this.service = service; }

  @PostMapping("/api/document-sets/{setId}/analyses")
  public ResponseEntity<AnalysisService.Snapshot> create(@PathVariable String setId) {
    var result = service.create(uuid(setId));
    return ResponseEntity.created(URI.create("/api/analyses/" + result.analysisId())).body(result);
  }
  @GetMapping("/api/document-sets/{setId}/analyses")
  public AnalysisService.Listing list(@PathVariable String setId,
      @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit) {
    return service.list(uuid(setId), offset, limit);
  }
  @GetMapping("/api/analyses/{analysisId}")
  public JsonNode get(@PathVariable String analysisId) { return service.get(uuid(analysisId)); }

  private UUID uuid(String value) {
    if (!value.matches("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"))
      throw new DocumentInputException(400, "INVALID_ID", "分析或文件组 ID 格式无效");
    return UUID.fromString(value);
  }
}
