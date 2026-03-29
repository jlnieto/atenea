CREATE TABLE session_deliverable (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INTEGER NOT NULL,
    title VARCHAR(200),
    content_markdown TEXT,
    content_json TEXT,
    input_snapshot_json TEXT,
    generation_notes TEXT,
    error_message TEXT,
    model VARCHAR(120),
    prompt_version VARCHAR(80),
    approved BOOLEAN NOT NULL DEFAULT FALSE,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_session_deliverable_session
        FOREIGN KEY (session_id) REFERENCES work_session (id) ON DELETE CASCADE,
    CONSTRAINT ck_session_deliverable_type
        CHECK (type IN ('WORK_TICKET', 'WORK_BREAKDOWN', 'PRICE_ESTIMATE')),
    CONSTRAINT ck_session_deliverable_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT ck_session_deliverable_version_positive
        CHECK (version > 0),
    CONSTRAINT ck_session_deliverable_approved_at_consistency
        CHECK (
            (approved = FALSE AND approved_at IS NULL)
            OR (approved = TRUE AND approved_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uk_session_deliverable_session_type_version
    ON session_deliverable (session_id, type, version);

CREATE INDEX idx_session_deliverable_session_id_type_version_desc
    ON session_deliverable (session_id, type, version DESC);

CREATE INDEX idx_session_deliverable_session_id_updated_at_desc
    ON session_deliverable (session_id, updated_at DESC);
