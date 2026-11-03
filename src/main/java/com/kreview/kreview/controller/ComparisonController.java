package com.kreview.kreview.controller;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.kreview.kreview.comparison.ChangeHunk;
import com.kreview.kreview.comparison.DiffEngine;
import com.kreview.kreview.comparison.DiffBudgetExceededException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.concurrent.Semaphore;

@RestController
public class ComparisonController {

  private final DiffEngine engine = new DiffEngine();
  private final Semaphore slot = new Semaphore(1);
  private final ObjectReader requestReader;

  public ComparisonController(ObjectMapper mapper) {
    // Immutable, endpoint-specific reader; leave shared Jackson configuration unchanged.
    requestReader = mapper.readerFor(JsonNode.class)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  @PostMapping(value = "/api/comparisons/preview",
      consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public PreviewResponse preview(@RequestBody byte[] body) {
    JsonNode request;
    try {
      request = requestReader.readValue(body);
    } catch (IOException ex) {
      // Parser exception messages can contain document text or user-controlled field names.
      throw new BadPreviewRequest("Invalid JSON request body");
    }
    if (request == null || !request.isObject()) {
      throw new BadPreviewRequest("Request body must be a JSON object");
    }
    var fields = request.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!field.equals("oldText") && !field.equals("newText")) {
        throw new BadPreviewRequest("Unknown fields: only oldText and newText are allowed");
      }
    }
    String oldText = requireText(request, "oldText");
    String newText = requireText(request, "newText");
    if (!slot.tryAcquire()) return busy();
    try {
      return new PreviewResponse("STRICT", engine.compare(oldText, newText));
    } catch (IllegalArgumentException ex) {
      // DiffEngine input errors contain only side and fixed limits, never input content.
      throw new BadPreviewRequest(ex.getMessage());
    } finally {
      slot.release();
    }
  }

  private PreviewResponse busy() { throw new PreviewBusy(); }

  @ExceptionHandler(DiffBudgetExceededException.class)
  public ResponseEntity<Map<String, String>> budget() {
    return ResponseEntity.status(422).body(Map.of("error", "Line diff exceeds work budget"));
  }
  @ExceptionHandler(PreviewBusy.class)
  public ResponseEntity<Map<String, String>> busyResponse() {
    return ResponseEntity.status(429).body(Map.of("error", "Comparison is busy"));
  }

  private String requireText(JsonNode request, String field) {
    JsonNode value = request.get(field);
    if (value == null || !value.isTextual()) {
      throw new BadPreviewRequest(field + " is required and must be a string");
    }
    return value.textValue();
  }

  @ExceptionHandler({BadPreviewRequest.class, HttpMessageNotReadableException.class})
  public ResponseEntity<Map<String, String>> handlePreviewError(Exception ex) {
    String message = ex instanceof BadPreviewRequest
        ? ex.getMessage() : "Invalid JSON request body";
    return ResponseEntity.badRequest().body(Map.of("error", message));
  }

  public record PreviewResponse(String mode, List<ChangeHunk> changes) {}

  private static final class BadPreviewRequest extends RuntimeException {
    private BadPreviewRequest(String message) {
      super(message);
    }
  }
  private static final class PreviewBusy extends RuntimeException {}
}
