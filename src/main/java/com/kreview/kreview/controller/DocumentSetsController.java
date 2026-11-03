package com.kreview.kreview.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kreview.kreview.document.*;
import com.kreview.kreview.document.storage.DocumentSetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/document-sets")
public class DocumentSetsController {
  private final TextDocumentDecoder decoder;
  private final DocumentSetService service;
  private final ObjectMapper metadataMapper = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public DocumentSetsController(TextDocumentDecoder decoder, DocumentSetService service) {
    this.decoder = decoder; this.service = service;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<DocumentSetService.Detail> create(HttpServletRequest request) throws Exception {
    var parts = request.getParts();
    if (parts.size() != 3 || parts.stream().map(Part::getName).distinct().count() != 3
        || !parts.stream().map(Part::getName).collect(Collectors.toSet()).equals(Set.of("metadata", "file1", "file2"))) {
      throw bad("INVALID_PARTS", "须提交 metadata、file1 和 file2，不能重复或增加其他文件/字段");
    }
    Map<String, Part> byName = parts.stream().collect(Collectors.toMap(Part::getName, Function.identity()));
    var meta = byName.get("metadata");
    if (meta.getContentType() == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(meta.getContentType()))) {
      throw new DocumentInputException(415, "UNSUPPORTED_METADATA_TYPE", "metadata 必须为 application/json");
    }
    byte[] metadata = read(meta, 16_384, "METADATA_TOO_LARGE", "metadata 最多 16 KiB");
    JsonNode json;
    try { json = metadataMapper.readTree(metadata); }
    catch (IOException ex) { throw bad("INVALID_METADATA", "metadata 必须是有效 JSON，不能有重复字段或多余内容"); }
    if (json == null || !json.isObject() || json.size() != 2
        || !json.path("displayName").isTextual() || !json.path("relation").isTextual()) {
      throw bad("INVALID_METADATA", "metadata 仅接受字符串 displayName 和 relation");
    }
    var first = decode(byName.get("file1"), 1);
    var second = decode(byName.get("file2"), 2);
    var result = service.create(json.get("displayName").textValue(), json.get("relation").textValue(), first, second);
    return ResponseEntity.created(URI.create("/api/document-sets/" + result.id())).body(result);
  }

  @GetMapping
  public DocumentSetService.Listing list(@RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(defaultValue = "20") int limit) {
    return service.list(offset, limit);
  }
  @GetMapping("/{id}")
  public DocumentSetService.Detail get(@PathVariable String id) { return service.get(uuid(id)); }
  @GetMapping("/{id}/versions/{versionId}/text")
  public DocumentSetService.Text text(@PathVariable String id, @PathVariable String versionId) {
    return service.text(uuid(id), uuid(versionId));
  }

  private DecodedDocument decode(Part part, int number) throws IOException {
    try {
      return decoder.decode(part.getSubmittedFileName(), read(part, Utf8TxtDecoder.MAX_BYTES, "FILE_TOO_LARGE", "每份文件最多 1 MiB"));
    } catch (DocumentInputException ex) {
      throw new DocumentInputException(ex.status(), ex.code(), "文件 " + number + "：" + ex.getMessage());
    }
  }
  private byte[] read(Part part, int max, String code, String message) throws IOException {
    if (part.getSize() > max) throw new DocumentInputException(413, code, message);
    try (var in = part.getInputStream()) {
      byte[] bytes = in.readNBytes(max + 1);
      if (bytes.length > max) throw new DocumentInputException(413, code, message);
      return bytes;
    }
  }
  private UUID uuid(String value) {
    if (!value.matches("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")) throw bad("INVALID_ID", "文件 ID 格式无效");
    return UUID.fromString(value);
  }
  private DocumentInputException bad(String code, String message) { return new DocumentInputException(400, code, message); }
}
