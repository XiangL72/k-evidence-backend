package com.kreview.kreview.document.storage;

import com.kreview.kreview.document.DecodedDocument;
import com.kreview.kreview.document.DocumentInputException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DocumentSetService {
  public record Version(UUID id, int sequence, String role, UUID targetVersionId,
      String originalFilename, String rawSha256, String textSha256,
      int byteLength, int utf16Length, int lineCount) {}
  public record Detail(UUID id, String displayName, Instant createdAt, List<Version> versions) {}
  public record Summary(UUID id, String displayName, Instant createdAt) {}
  public record Listing(List<Summary> items, long total, int offset, int limit) {}
  public record Text(UUID versionId, String text, String textSha256) {}

  private final DocumentSetRepository sets;
  private final DocumentVersionRepository versions;
  private final TransactionTemplate transaction;
  public DocumentSetService(DocumentSetRepository sets, DocumentVersionRepository versions,
                            PlatformTransactionManager manager) {
    this.sets = sets; this.versions = versions; transaction = new TransactionTemplate(manager);
  }

  public Detail create(String name, String relation, DecodedDocument first, DecodedDocument second) {
    if (name == null || name.isBlank() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)) {
      throw new DocumentInputException(400, "INVALID_METADATA", "名称须为 1–100 个字符，且不含控制字符");
    }
    if (!"REVISION".equals(relation) && !"SUPPLEMENT".equals(relation)) {
      throw new DocumentInputException(400, "INVALID_RELATION", "请选择完整修订版或补充协议");
    }
    return transaction.execute(status -> {
      Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); UUID id = UUID.randomUUID(), baseId = UUID.randomUUID();
      var set = sets.saveAndFlush(new DocumentSetEntity(id, name, now));
      var base = versions.saveAndFlush(new DocumentVersionEntity(baseId, id, (short) 1, "BASE", null, first, now));
      var next = versions.saveAndFlush(new DocumentVersionEntity(UUID.randomUUID(), id, (short) 2, relation, baseId, second, now));
      return new Detail(id, set.displayName, now, List.of(view(base), view(next)));
    });
  }

  public Detail get(UUID id) {
    var set = sets.findById(id).orElseThrow(DocumentSetService::notFound);
    return new Detail(set.id, set.displayName, set.createdAt,
        versions.findBySetIdOrderBySequence(id).stream().map(DocumentSetService::view).toList());
  }

  public Listing list(int offset, int limit) {
    if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 100) {
      throw new DocumentInputException(400, "INVALID_PAGINATION", "offset 须为 0–1000000，limit 须为 1–100");
    }
    // Fetch a bounded pair of pages to support offsets not divisible by the page size.
    var sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    var page = sets.findAll(PageRequest.of(offset / limit, limit, sort));
    var rows = new java.util.ArrayList<>(page.getContent());
    if (offset % limit != 0 && page.hasNext()) rows.addAll(sets.findAll(PageRequest.of(offset / limit + 1, limit, sort)).getContent());
    var items = rows.stream().skip(offset % limit).limit(limit)
        .map(s -> new Summary(s.id, s.displayName, s.createdAt)).toList();
    return new Listing(items, page.getTotalElements(), offset, limit);
  }

  public Text text(UUID setId, UUID versionId) {
    var doc = versions.findByIdAndSetId(versionId, setId).orElseThrow(DocumentSetService::notFound);
    return new Text(doc.id, doc.decodedText, doc.textSha256);
  }
  private static DocumentInputException notFound() { return new DocumentInputException(404, "NOT_FOUND", "文件组或文件不存在"); }
  private static Version view(DocumentVersionEntity v) {
    return new Version(v.id, v.sequence, v.role, v.targetVersionId, v.originalFilename,
        v.rawSha256, v.textSha256, v.byteLength, v.utf16Length, v.lineCount);
  }
}
