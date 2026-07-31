CREATE TABLE validation_operation (
    id UUID PRIMARY KEY,
    work_session_id BIGINT NOT NULL REFERENCES work_session(id),
    operation VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    source_tree_fingerprint_sha256 VARCHAR(64) NOT NULL,
    definition_revision VARCHAR(80) NOT NULL,
    identity_sha256 VARCHAR(64) NOT NULL UNIQUE,
    exit_code INTEGER,
    duration_millis BIGINT,
    artifact_manifest_sha256 VARCHAR(64),
    summary VARCHAR(500),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_validation_operation_kind
        CHECK (operation IN ('BACKEND_TEST', 'WEB_BUILD', 'ANDROID_BUILD')),
    CONSTRAINT ck_validation_operation_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED')),
    CONSTRAINT ck_validation_operation_source
        CHECK (source_tree_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_validation_operation_identity
        CHECK (identity_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_validation_operation_artifact
        CHECK (
            artifact_manifest_sha256 IS NULL
            OR artifact_manifest_sha256 ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT ck_validation_operation_terminal
        CHECK (
            (status = 'RUNNING'
                AND exit_code IS NULL
                AND duration_millis IS NULL
                AND finished_at IS NULL)
            OR (status IN ('SUCCEEDED', 'FAILED', 'BLOCKED')
                AND duration_millis >= 0
                AND finished_at IS NOT NULL)
        )
);

CREATE INDEX idx_validation_operation_session_tree
    ON validation_operation (
        work_session_id,
        source_tree_fingerprint_sha256,
        operation
    );
