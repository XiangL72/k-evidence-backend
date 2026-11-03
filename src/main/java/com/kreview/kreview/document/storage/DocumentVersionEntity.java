package com.kreview.kreview.document.storage;

import com.kreview.kreview.document.DecodedDocument;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "document_version")
public class DocumentVersionEntity {
  @Id UUID id;
  @Column(name = "set_id", nullable = false) UUID setId;
  @Column(nullable = false) short sequence;
  @Column(nullable = false, length = 16) String role;
  @Column(name = "target_version_id") UUID targetVersionId;
  @Column(name = "original_filename", nullable = false, length = 255) String originalFilename;
  @JdbcTypeCode(SqlTypes.VARBINARY)
  @Column(name = "raw_bytes", nullable = false, columnDefinition = "bytea") byte[] rawBytes;
  @Column(name = "decoded_text", nullable = false, columnDefinition = "text") String decodedText;
  @Column(name = "raw_sha256", nullable = false, length = 64) String rawSha256;
  @Column(name = "text_sha256", nullable = false, length = 64) String textSha256;
  @Column(name = "byte_length", nullable = false) int byteLength;
  @Column(name = "utf16_length", nullable = false) int utf16Length;
  @Column(name = "line_count", nullable = false) int lineCount;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected DocumentVersionEntity() {}
  DocumentVersionEntity(UUID id, UUID setId, short sequence, String role, UUID target,
                        DecodedDocument doc, Instant createdAt) {
    this.id = id; this.setId = setId; this.sequence = sequence; this.role = role;
    this.targetVersionId = target; this.originalFilename = doc.filename();
    this.rawBytes = doc.rawBytes(); this.decodedText = doc.text();
    this.rawSha256 = doc.rawSha256(); this.textSha256 = doc.textSha256();
    this.byteLength = rawBytes.length; this.utf16Length = decodedText.length();
    this.lineCount = doc.lineCount(); this.createdAt = createdAt;
  }
}
