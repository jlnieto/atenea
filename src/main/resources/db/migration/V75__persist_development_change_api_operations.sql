CREATE TABLE development_change_operation (
    id BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    operator_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    operation_kind VARCHAR(24) NOT NULL,
    request_fingerprint_sha256 VARCHAR(64) NOT NULL,
    target_fingerprint_sha256 VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
    revision BIGINT NOT NULL DEFAULT 0,
    development_change_id BIGINT,
    work_session_id BIGINT,
    receipt_sha256 VARCHAR(64),
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_development_change_operation_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_development_change_operation_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT fk_development_change_operation_change
        FOREIGN KEY (development_change_id, project_id)
        REFERENCES development_change (id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_development_change_operation_session
        FOREIGN KEY (work_session_id) REFERENCES work_session (id) ON DELETE RESTRICT,
    CONSTRAINT uk_development_change_operation_idempotency
        UNIQUE (operator_id, operation_kind, idempotency_key),
    CONSTRAINT ck_development_change_operation_kind
        CHECK (operation_kind IN ('CREATE', 'PAUSE', 'ABANDON', 'SESSION_BIND')),
    CONSTRAINT ck_development_change_operation_fingerprints
        CHECK (request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
            AND target_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_development_change_operation_state
        CHECK (state IN ('REQUESTED', 'SUCCEEDED')),
    CONSTRAINT ck_development_change_operation_revision
        CHECK (revision >= 0),
    CONSTRAINT ck_development_change_operation_receipt
        CHECK (receipt_sha256 IS NULL OR receipt_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_development_change_operation_timestamps
        CHECK (updated_at >= requested_at
            AND (completed_at IS NULL OR completed_at >= requested_at)),
    CONSTRAINT ck_development_change_operation_progress
        CHECK (
            (state = 'REQUESTED' AND revision = 0
                AND receipt_sha256 IS NULL AND completed_at IS NULL)
            OR
            (state = 'SUCCEEDED' AND revision = 1
                AND receipt_sha256 IS NOT NULL AND completed_at IS NOT NULL)
        ),
    CONSTRAINT ck_development_change_operation_targets
        CHECK (
            (operation_kind = 'CREATE'
                AND work_session_id IS NULL
                AND (state = 'REQUESTED' OR development_change_id IS NOT NULL))
            OR
            (operation_kind IN ('PAUSE', 'ABANDON')
                AND development_change_id IS NOT NULL
                AND work_session_id IS NULL)
            OR
            (operation_kind = 'SESSION_BIND'
                AND development_change_id IS NOT NULL
                AND work_session_id IS NOT NULL)
        )
);

CREATE INDEX idx_development_change_operation_change
    ON development_change_operation (development_change_id, requested_at DESC)
    WHERE development_change_id IS NOT NULL;

CREATE INDEX idx_development_change_operation_session
    ON development_change_operation (work_session_id, requested_at DESC)
    WHERE work_session_id IS NOT NULL;

CREATE FUNCTION reject_terminal_development_change_operation_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'development change operations cannot be deleted';
    END IF;
    IF OLD.state = 'SUCCEEDED' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'terminal development change operations are immutable';
    END IF;
    IF NEW.operation_id IS DISTINCT FROM OLD.operation_id
            OR NEW.operator_id IS DISTINCT FROM OLD.operator_id
            OR NEW.project_id IS DISTINCT FROM OLD.project_id
            OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
            OR NEW.operation_kind IS DISTINCT FROM OLD.operation_kind
            OR NEW.request_fingerprint_sha256 IS DISTINCT FROM OLD.request_fingerprint_sha256
            OR NEW.target_fingerprint_sha256 IS DISTINCT FROM OLD.target_fingerprint_sha256
            OR NEW.requested_at IS DISTINCT FROM OLD.requested_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'development change operation identity is immutable';
    END IF;
    IF NEW.revision <= OLD.revision THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'development change operation revision must advance';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_development_change_operation_terminal_immutable
BEFORE UPDATE OR DELETE ON development_change_operation
FOR EACH ROW
EXECUTE FUNCTION reject_terminal_development_change_operation_mutation();
