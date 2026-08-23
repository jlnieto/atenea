CREATE TABLE v2_global_capability_gate (
    capability VARCHAR(80) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_v2_global_capability_gate_name
        CHECK (capability ~ '^[a-z][a-z0-9-]{2,79}$'),
    CONSTRAINT ck_v2_global_capability_gate_revision
        CHECK (revision >= 0),
    CONSTRAINT ck_v2_global_capability_gate_timestamps
        CHECK (updated_at >= created_at)
);

CREATE TABLE v2_project_capability_policy (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    capability VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    policy_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_v2_project_capability_policy_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT uk_v2_project_capability_policy
        UNIQUE (project_id, capability),
    CONSTRAINT ck_v2_project_capability_policy_name
        CHECK (capability ~ '^[a-z][a-z0-9-]{2,79}$'),
    CONSTRAINT ck_v2_project_capability_policy_revision
        CHECK (policy_revision >= 1),
    CONSTRAINT ck_v2_project_capability_policy_timestamps
        CHECK (updated_at >= created_at)
);

CREATE INDEX idx_v2_project_capability_policy_enabled
    ON v2_project_capability_policy (capability, project_id, policy_revision)
    WHERE enabled;

CREATE TABLE v2_audit_event (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    project_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    capability VARCHAR(80) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    state VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL,
    request_fingerprint_sha256 VARCHAR(64) NOT NULL,
    target_fingerprint_sha256 VARCHAR(64) NOT NULL,
    failure_category VARCHAR(24),
    failure_code VARCHAR(80),
    item_count INTEGER NOT NULL DEFAULT 0,
    duration_millis BIGINT NOT NULL DEFAULT 0,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_v2_audit_event_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT fk_v2_audit_event_actor
        FOREIGN KEY (actor_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT uk_v2_audit_event_operation_revision
        UNIQUE (operation_id, revision, event_type),
    CONSTRAINT ck_v2_audit_event_capability
        CHECK (capability ~ '^[a-z][a-z0-9-]{2,79}$'),
    CONSTRAINT ck_v2_audit_event_type
        CHECK (event_type ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_v2_audit_event_state
        CHECK (state ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT ck_v2_audit_event_revision
        CHECK (revision >= 0),
    CONSTRAINT ck_v2_audit_event_fingerprints
        CHECK (request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
            AND target_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_v2_audit_event_failure_category
        CHECK (failure_category IS NULL OR failure_category IN (
            'TRANSPORT', 'CAPACITY', 'VALIDATION', 'POLICY', 'OWNERSHIP'
        )),
    CONSTRAINT ck_v2_audit_event_failure
        CHECK ((failure_category IS NULL AND failure_code IS NULL)
            OR (failure_category IS NOT NULL
                AND failure_code ~ '^[A-Z][A-Z0-9_]{2,79}$')),
    CONSTRAINT ck_v2_audit_event_counts
        CHECK (item_count >= 0 AND duration_millis >= 0)
);

CREATE FUNCTION reject_v2_audit_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'v2 audit events are append-only';
END;
$$;

CREATE TRIGGER trg_v2_audit_event_append_only
BEFORE UPDATE OR DELETE ON v2_audit_event
FOR EACH ROW
EXECUTE FUNCTION reject_v2_audit_event_mutation();

CREATE TABLE v2_outbox_event (
    id UUID PRIMARY KEY,
    audit_event_id UUID NOT NULL UNIQUE,
    operation_id UUID NOT NULL,
    capability VARCHAR(80) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    revision BIGINT NOT NULL,
    deduplication_sha256 VARCHAR(64) NOT NULL UNIQUE,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_v2_outbox_event_audit
        FOREIGN KEY (audit_event_id) REFERENCES v2_audit_event (id) ON DELETE RESTRICT,
    CONSTRAINT ck_v2_outbox_event_capability
        CHECK (capability ~ '^[a-z][a-z0-9-]{2,79}$'),
    CONSTRAINT ck_v2_outbox_event_type
        CHECK (event_type ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_v2_outbox_event_revision
        CHECK (revision >= 0),
    CONSTRAINT ck_v2_outbox_event_digest
        CHECK (deduplication_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_v2_outbox_event_state
        CHECK (state IN ('PENDING', 'PUBLISHING', 'RETRY_WAIT', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_v2_outbox_event_attempts
        CHECK (attempt_count BETWEEN 0 AND 10),
    CONSTRAINT ck_v2_outbox_event_failure
        CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    CONSTRAINT ck_v2_outbox_event_timestamps
        CHECK (updated_at >= created_at
            AND (published_at IS NULL OR published_at >= created_at)),
    CONSTRAINT ck_v2_outbox_event_projection
        CHECK (
            (state = 'PENDING' AND attempt_count = 0
                AND next_attempt_at IS NULL AND published_at IS NULL
                AND failure_code IS NULL)
            OR (state = 'PUBLISHING' AND attempt_count BETWEEN 1 AND 10
                AND next_attempt_at IS NULL AND published_at IS NULL)
            OR (state = 'RETRY_WAIT' AND attempt_count BETWEEN 1 AND 9
                AND next_attempt_at IS NOT NULL AND published_at IS NULL
                AND failure_code IS NOT NULL)
            OR (state = 'PUBLISHED' AND attempt_count BETWEEN 1 AND 10
                AND next_attempt_at IS NULL AND published_at IS NOT NULL
                AND failure_code IS NULL)
            OR (state = 'FAILED' AND attempt_count BETWEEN 1 AND 10
                AND next_attempt_at IS NULL AND published_at IS NULL
                AND failure_code IS NOT NULL)
        )
);

CREATE INDEX idx_v2_outbox_event_dispatch
    ON v2_outbox_event (state, next_attempt_at, created_at)
    WHERE state IN ('PENDING', 'RETRY_WAIT');
