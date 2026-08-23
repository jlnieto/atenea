ALTER TABLE work_session
    ADD COLUMN fresh_start_operation_id UUID;

CREATE UNIQUE INDEX uk_work_session_fresh_start_operation
    ON work_session (fresh_start_operation_id)
    WHERE fresh_start_operation_id IS NOT NULL;

CREATE TABLE fresh_work_session_operation (
    id BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    idempotency_key UUID NOT NULL,
    request_fingerprint_sha256 VARCHAR(64) NOT NULL,
    operator_id BIGINT NOT NULL REFERENCES operator_account(id),
    source_work_session_id BIGINT NOT NULL UNIQUE REFERENCES work_session(id),
    source_agent_run_id BIGINT NOT NULL REFERENCES agent_run(id),
    requested_commit VARCHAR(40) NOT NULL,
    canonical_commit VARCHAR(40) NOT NULL,
    relationship_fingerprint_sha256 VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    result_work_session_id BIGINT UNIQUE REFERENCES work_session(id),
    requested_at TIMESTAMPTZ NOT NULL,
    source_released_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_fresh_work_session_operator_idempotency
        UNIQUE (operator_id, idempotency_key),
    CONSTRAINT ck_fresh_work_session_fingerprints CHECK (
        request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
        AND relationship_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
        AND requested_commit ~ '^[0-9a-f]{40}$'
        AND canonical_commit ~ '^[0-9a-f]{40}$'
        AND requested_commit <> canonical_commit
    ),
    CONSTRAINT ck_fresh_work_session_state CHECK (
        state IN ('REQUESTED', 'SOURCE_RELEASED', 'COMPLETED')
    ),
    CONSTRAINT ck_fresh_work_session_progress CHECK (
        (state = 'REQUESTED'
            AND source_released_at IS NULL
            AND result_work_session_id IS NULL
            AND completed_at IS NULL)
        OR (state = 'SOURCE_RELEASED'
            AND source_released_at IS NOT NULL
            AND result_work_session_id IS NULL
            AND completed_at IS NULL)
        OR (state = 'COMPLETED'
            AND source_released_at IS NOT NULL
            AND result_work_session_id IS NOT NULL
            AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idx_fresh_work_session_resume
    ON fresh_work_session_operation (state, updated_at)
    WHERE state <> 'COMPLETED';
