package com.kreview.kreview.document.storage;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_set")
public class DocumentSetEntity {
  @Id UUID id;
  @Column(name = "display_name", nullable = false, length = 100) String displayName;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected DocumentSetEntity() {}
  DocumentSetEntity(UUID id, String displayName, Instant createdAt) {
    this.id = id; this.displayName = displayName; this.createdAt = createdAt;
  }
}
