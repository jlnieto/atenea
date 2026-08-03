CREATE TABLE work_session_attachment (
    id UUID PRIMARY KEY,
    work_session_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    agent_run_id BIGINT,
    source VARCHAR(32) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    original_filename VARCHAR(180) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    retention_class VARCHAR(16) NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    worker_id VARCHAR(80) NOT NULL,
    storage_identity VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_work_session_attachment_session
        FOREIGN KEY (work_session_id) REFERENCES work_session (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_session_attachment_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_session_attachment_agent_run
        FOREIGN KEY (agent_run_id) REFERENCES agent_run (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_session_attachment_worker
        FOREIGN KEY (worker_id) REFERENCES worker_node (id) ON DELETE RESTRICT,
    CONSTRAINT uk_work_session_attachment_storage
        UNIQUE (worker_id, storage_identity),
    CONSTRAINT ck_work_session_attachment_source
        CHECK (source IN ('OPERATOR_UPLOAD', 'BROWSER_SCREENSHOT', 'BROWSER_TRACE', 'REPORT')),
    CONSTRAINT ck_work_session_attachment_kind
        CHECK (kind IN ('IMAGE', 'TRACE', 'REPORT', 'FILE')),
    CONSTRAINT ck_work_session_attachment_retention
        CHECK (retention_class IN ('TRANSIENT', 'SESSION', 'EVIDENCE')),
    CONSTRAINT ck_work_session_attachment_size
        CHECK (size_bytes > 0 AND size_bytes <= 16777216),
    CONSTRAINT ck_work_session_attachment_sha256
        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_work_session_attachment_retention_time
        CHECK (retain_until > created_at)
);

CREATE INDEX idx_work_session_attachment_session_order
    ON work_session_attachment (work_session_id, created_at DESC, id DESC);

CREATE INDEX idx_work_session_attachment_session_source_order
    ON work_session_attachment (work_session_id, source, created_at DESC, id DESC);

CREATE INDEX idx_work_session_attachment_agent_run
    ON work_session_attachment (agent_run_id)
    WHERE agent_run_id IS NOT NULL;

CREATE INDEX idx_work_session_attachment_retention
    ON work_session_attachment (retention_class, retain_until);
