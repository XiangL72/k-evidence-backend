package com.kreview.kreview.document.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersionEntity, UUID> {
  List<DocumentVersionEntity> findBySetIdOrderBySequence(UUID setId);
  Optional<DocumentVersionEntity> findByIdAndSetId(UUID id, UUID setId);
}
