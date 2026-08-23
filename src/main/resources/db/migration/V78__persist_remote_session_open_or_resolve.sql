-- M2.5 task 1.1: additive persistence for the disabled, local-only
-- OPEN_OR_RESOLVE_REMOTE_SESSION operation. This migration creates no rows,
-- performs no backfill and changes no existing DevelopmentChange/WorkSession.

CREATE TABLE remote_session_operation (
    id BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    operator_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    development_change_id BIGINT NOT NULL,
    work_session_id BIGINT,
    idempotency_key UUID NOT NULL,
    operation_kind VARCHAR(48) NOT NULL,
    expected_change_revision BIGINT NOT NULL,
    request_fingerprint_sha256 VARCHAR(64) NOT NULL,
    target_fingerprint_sha256 VARCHAR(64) NOT NULL,
    source_fingerprint_sha256 VARCHAR(64) NOT NULL,
    ownership_fingerprint_sha256 VARCHAR(64) NOT NULL,
    beta_policy_revision BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    revision BIGINT NOT NULL,
    resolution VARCHAR(16),
    result_change_revision BIGINT,
    result_session_state VARCHAR(32),
    result_remote_session_id UUID,
    rejection_class VARCHAR(16),
    failure_code VARCHAR(96),
    next_action VARCHAR(48),
    receipt_sha256 VARCHAR(64),
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_remote_session_operation_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_remote_session_operation_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT fk_remote_session_operation_change
        FOREIGN KEY (development_change_id, project_id)
        REFERENCES development_change (id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_remote_session_operation_session
        FOREIGN KEY (work_session_id) REFERENCES work_session (id) ON DELETE RESTRICT,
    CONSTRAINT uk_remote_session_operation_idempotency
        UNIQUE (operator_id, operation_kind, idempotency_key),
    CONSTRAINT ck_remote_session_operation_kind
        CHECK (operation_kind = 'OPEN_OR_RESOLVE_REMOTE_SESSION'),
    CONSTRAINT ck_remote_session_operation_expected_revision
        CHECK (expected_change_revision >= 0),
    CONSTRAINT ck_remote_session_operation_fingerprints
        CHECK (request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
            AND target_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
            AND source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
            AND ownership_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_remote_session_operation_policy_revision
        CHECK (beta_policy_revision >= 0),
    CONSTRAINT ck_remote_session_operation_state
        CHECK (state IN ('REQUESTED', 'SUCCEEDED', 'REJECTED', 'BLOCKED')),
    CONSTRAINT ck_remote_session_operation_revision
        CHECK (revision >= 0),
    CONSTRAINT ck_remote_session_operation_resolution
        CHECK (resolution IS NULL OR resolution IN ('CREATED', 'RESOLVED')),
    CONSTRAINT ck_remote_session_operation_session_state
        CHECK (result_session_state IS NULL
            OR result_session_state IN ('OPEN', 'CLOSING', 'DRAFT_BLOCKED', 'CLOSED')),
    CONSTRAINT ck_remote_session_operation_rejection
        CHECK (rejection_class IS NULL
            OR rejection_class IN ('VALIDATION', 'POLICY', 'OWNERSHIP', 'CAPACITY', 'UNSUPPORTED')),
    CONSTRAINT ck_remote_session_operation_failure_code
        CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{2,95}$'),
    CONSTRAINT ck_remote_session_operation_receipt
        CHECK (receipt_sha256 IS NULL OR receipt_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_remote_session_operation_timestamps
        CHECK (updated_at >= requested_at
            AND (completed_at IS NULL OR completed_at >= requested_at)),
    CONSTRAINT ck_remote_session_operation_progress
        CHECK (
            (state = 'REQUESTED'
                AND revision = 0
                AND work_session_id IS NULL
                AND resolution IS NULL
                AND result_change_revision IS NULL
                AND result_session_state IS NULL
                AND result_remote_session_id IS NULL
                AND rejection_class IS NULL
                AND failure_code IS NULL
                AND next_action IS NULL
                AND receipt_sha256 IS NULL
                AND completed_at IS NULL)
            OR
            (state = 'SUCCEEDED'
                AND revision = 1
                AND work_session_id IS NOT NULL
                AND resolution IS NOT NULL
                AND result_change_revision IS NOT NULL
                AND result_session_state IS NOT NULL
                AND result_remote_session_id IS NOT NULL
                AND rejection_class IS NULL
                AND failure_code IS NULL
                AND next_action IS NOT NULL
                AND receipt_sha256 IS NOT NULL
                AND completed_at IS NOT NULL)
            OR
            (state IN ('REJECTED', 'BLOCKED')
                AND revision = 1
                AND resolution IS NULL
                AND result_change_revision IS NULL
                AND result_session_state IS NULL
                AND result_remote_session_id IS NULL
                AND rejection_class IS NOT NULL
                AND failure_code IS NOT NULL
                AND next_action IS NOT NULL
                AND receipt_sha256 IS NOT NULL
                AND completed_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uk_remote_session_operation_active_change
    ON remote_session_operation (development_change_id)
    WHERE state = 'REQUESTED';

CREATE INDEX idx_remote_session_operation_change_terminal
    ON remote_session_operation (development_change_id, completed_at DESC)
    WHERE state IN ('SUCCEEDED', 'REJECTED', 'BLOCKED');

CREATE INDEX idx_remote_session_operation_session
    ON remote_session_operation (work_session_id, completed_at DESC)
    WHERE work_session_id IS NOT NULL;

CREATE FUNCTION enforce_remote_session_operation_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote session operations cannot be deleted';
    END IF;

    IF OLD.state IN ('SUCCEEDED', 'REJECTED', 'BLOCKED') THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'terminal remote session operation receipts are immutable';
    END IF;

    IF NEW.operation_id IS DISTINCT FROM OLD.operation_id
            OR NEW.operator_id IS DISTINCT FROM OLD.operator_id
            OR NEW.project_id IS DISTINCT FROM OLD.project_id
            OR NEW.development_change_id IS DISTINCT FROM OLD.development_change_id
            OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
            OR NEW.operation_kind IS DISTINCT FROM OLD.operation_kind
            OR NEW.expected_change_revision IS DISTINCT FROM OLD.expected_change_revision
            OR NEW.request_fingerprint_sha256 IS DISTINCT FROM OLD.request_fingerprint_sha256
            OR NEW.target_fingerprint_sha256 IS DISTINCT FROM OLD.target_fingerprint_sha256
            OR NEW.source_fingerprint_sha256 IS DISTINCT FROM OLD.source_fingerprint_sha256
            OR NEW.ownership_fingerprint_sha256 IS DISTINCT FROM OLD.ownership_fingerprint_sha256
            OR NEW.beta_policy_revision IS DISTINCT FROM OLD.beta_policy_revision
            OR NEW.requested_at IS DISTINCT FROM OLD.requested_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote session operation identity is immutable';
    END IF;

    IF OLD.work_session_id IS NOT NULL
            AND NEW.work_session_id IS DISTINCT FROM OLD.work_session_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote session operation target session is immutable';
    END IF;

    IF NEW.revision <= OLD.revision THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote session operation revision must advance';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_remote_session_operation_immutable
BEFORE UPDATE OR DELETE ON remote_session_operation
FOR EACH ROW
EXECUTE FUNCTION enforce_remote_session_operation_immutability();
