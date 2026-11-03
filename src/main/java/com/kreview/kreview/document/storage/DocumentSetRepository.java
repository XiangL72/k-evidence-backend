package com.kreview.kreview.document.storage;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentSetRepository extends JpaRepository<DocumentSetEntity, UUID> {}
