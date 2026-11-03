CREATE TABLE document_set (
    id UUID PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL CHECK (length(btrim(display_name)) > 0),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE document_version (
    id UUID PRIMARY KEY,
    set_id UUID NOT NULL REFERENCES document_set(id),
    sequence SMALLINT NOT NULL CHECK (sequence IN (1, 2)),
    role VARCHAR(16) NOT NULL CHECK (role IN ('BASE', 'REVISION', 'SUPPLEMENT')),
    target_version_id UUID,
    target_sequence SMALLINT GENERATED ALWAYS AS (CASE WHEN sequence = 2 THEN 1 ELSE NULL END) STORED,
    original_filename VARCHAR(255) NOT NULL CHECK (length(original_filename) > 0),
    raw_bytes BYTEA NOT NULL,
    decoded_text TEXT NOT NULL,
    raw_sha256 VARCHAR(64) NOT NULL CHECK (raw_sha256 ~ '^[0-9a-f]{64}$'),
    text_sha256 VARCHAR(64) NOT NULL CHECK (text_sha256 ~ '^[0-9a-f]{64}$'),
    byte_length INTEGER NOT NULL CHECK (byte_length BETWEEN 0 AND 1048576 AND byte_length = octet_length(raw_bytes)),
    utf16_length INTEGER NOT NULL CHECK (utf16_length BETWEEN 0 AND 100000),
    line_count INTEGER NOT NULL CHECK (line_count BETWEEN 0 AND 5000),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (set_id, sequence),
    UNIQUE (set_id, id, sequence),
    CHECK ((sequence = 1 AND role = 'BASE' AND target_version_id IS NULL)
        OR (sequence = 2 AND role IN ('REVISION', 'SUPPLEMENT') AND target_version_id IS NOT NULL)),
    FOREIGN KEY (set_id, target_version_id, target_sequence) REFERENCES document_version(set_id, id, sequence)
);
CREATE TABLE analysis_run (
    id UUID PRIMARY KEY,
    set_id UUID NOT NULL REFERENCES document_set(id),
    base_version_id UUID NOT NULL,
    next_version_id UUID NOT NULL,
    base_sequence SMALLINT NOT NULL DEFAULT 1 CHECK (base_sequence = 1),
    next_sequence SMALLINT NOT NULL DEFAULT 2 CHECK (next_sequence = 2),
    algorithm_version VARCHAR(100) NOT NULL,
    rules_version VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    config_json JSONB NOT NULL CHECK (jsonb_typeof(config_json) = 'object'),
    result_json JSONB NOT NULL CHECK (jsonb_typeof(result_json) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (set_id, base_version_id, base_sequence) REFERENCES document_version(set_id, id, sequence),
    FOREIGN KEY (set_id, next_version_id, next_sequence) REFERENCES document_version(set_id, id, sequence)
);
CREATE INDEX document_set_created ON document_set(created_at DESC, id DESC);
CREATE INDEX analysis_run_set_created ON analysis_run(set_id, created_at DESC, id DESC);
