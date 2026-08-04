ALTER TABLE work_session
    ADD COLUMN remote_close_state VARCHAR(32),
    ADD COLUMN remote_close_operation_id UUID,
    ADD COLUMN remote_close_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN remote_close_receipt_sha256 VARCHAR(64),
    ADD COLUMN remote_close_error_code VARCHAR(80),
    ADD COLUMN remote_close_requested_at TIMESTAMPTZ,
    ADD COLUMN remote_close_updated_at TIMESTAMPTZ,
    ADD COLUMN remote_close_released_at TIMESTAMPTZ;

UPDATE work_session
SET remote_close_state = CASE
        WHEN execution_target = 'LOCAL' THEN 'NOT_REQUIRED'
        WHEN status = 'CLOSED' THEN 'UNVERIFIED_LEGACY'
        ELSE 'NOT_STARTED'
    END;

ALTER TABLE work_session
    ALTER COLUMN remote_close_state SET NOT NULL,
    ALTER COLUMN remote_close_state SET DEFAULT 'NOT_REQUIRED',
    ADD CONSTRAINT ck_work_session_remote_close_state
        CHECK (remote_close_state IN (
            'NOT_REQUIRED', 'NOT_STARTED', 'REQUESTED', 'RECONCILING',
            'BLOCKED', 'RELEASED', 'UNVERIFIED_LEGACY'
        )),
    ADD CONSTRAINT ck_work_session_remote_close_revision
        CHECK (remote_close_revision >= 0),
    ADD CONSTRAINT ck_work_session_remote_close_receipt
        CHECK (
            remote_close_receipt_sha256 IS NULL
            OR remote_close_receipt_sha256 ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT ck_work_session_remote_close_error
        CHECK (
            remote_close_error_code IS NULL
            OR remote_close_error_code ~ '^[A-Z][A-Z0-9_]{2,79}$'
        ),
    ADD CONSTRAINT ck_work_session_remote_close_projection
        CHECK (
            (remote_close_state = 'NOT_REQUIRED'
                AND execution_target = 'LOCAL'
                AND remote_close_operation_id IS NULL
                AND remote_close_revision = 0
                AND remote_close_receipt_sha256 IS NULL
                AND remote_close_error_code IS NULL
                AND remote_close_requested_at IS NULL
                AND remote_close_updated_at IS NULL
                AND remote_close_released_at IS NULL)
            OR (remote_close_state = 'NOT_STARTED'
                AND execution_target = 'REMOTE'
                AND status <> 'CLOSED'
                AND remote_close_operation_id IS NULL
                AND remote_close_revision = 0
                AND remote_close_receipt_sha256 IS NULL
                AND remote_close_error_code IS NULL
                AND remote_close_requested_at IS NULL
                AND remote_close_updated_at IS NULL
                AND remote_close_released_at IS NULL)
            OR (remote_close_state = 'UNVERIFIED_LEGACY'
                AND execution_target = 'REMOTE'
                AND status = 'CLOSED'
                AND remote_close_operation_id IS NULL
                AND remote_close_revision = 0
                AND remote_close_receipt_sha256 IS NULL
                AND remote_close_error_code IS NULL
                AND remote_close_requested_at IS NULL
                AND remote_close_updated_at IS NULL
                AND remote_close_released_at IS NULL)
            OR (remote_close_state = 'REQUESTED'
                AND execution_target = 'REMOTE'
                AND status IN ('CLOSING', 'CLOSED')
                AND remote_close_operation_id IS NOT NULL
                AND remote_close_revision >= 1
                AND remote_close_receipt_sha256 IS NULL
                AND remote_close_error_code IS NULL
                AND remote_close_requested_at IS NOT NULL
                AND remote_close_updated_at IS NOT NULL
                AND remote_close_released_at IS NULL)
            OR (remote_close_state = 'RECONCILING'
                AND execution_target = 'REMOTE'
                AND status IN ('CLOSING', 'CLOSED')
                AND remote_close_operation_id IS NOT NULL
                AND remote_close_revision >= 1
                AND remote_close_receipt_sha256 IS NULL
                AND remote_close_requested_at IS NOT NULL
                AND remote_close_updated_at IS NOT NULL
                AND remote_close_released_at IS NULL)
            OR (remote_close_state = 'BLOCKED'
                AND execution_target = 'REMOTE'
                AND status IN ('CLOSING', 'CLOSED')
                AND remote_close_operation_id IS NOT NULL
                AND remote_close_revision >= 1
                AND remote_close_receipt_sha256 IS NULL
                AND remote_close_error_code IS NOT NULL
                AND remote_close_requested_at IS NOT NULL
                AND remote_close_updated_at IS NOT NULL
                AND remote_close_released_at IS NULL)
            OR (remote_close_state = 'RELEASED'
                AND execution_target = 'REMOTE'
                AND status = 'CLOSED'
                AND remote_close_operation_id IS NOT NULL
                AND remote_close_revision >= 1
                AND remote_close_receipt_sha256 IS NOT NULL
                AND remote_close_error_code IS NULL
                AND remote_close_requested_at IS NOT NULL
                AND remote_close_updated_at IS NOT NULL
                AND remote_close_released_at IS NOT NULL)
        );

CREATE UNIQUE INDEX uk_work_session_remote_close_operation
    ON work_session (remote_close_operation_id)
    WHERE remote_close_operation_id IS NOT NULL;

CREATE INDEX idx_work_session_remote_close_reconcile
    ON work_session (remote_close_state, remote_close_updated_at, id)
    WHERE remote_close_state IN ('REQUESTED', 'RECONCILING');

CREATE FUNCTION enforce_work_session_remote_close_monotonicity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.remote_close_operation_id IS NOT NULL
            AND NEW.remote_close_operation_id IS DISTINCT FROM OLD.remote_close_operation_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote close operation identity is immutable';
    END IF;

    IF NEW.remote_close_revision < OLD.remote_close_revision THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote close revision cannot decrease';
    END IF;

    IF OLD.remote_close_requested_at IS NOT NULL
            AND NEW.remote_close_requested_at IS DISTINCT FROM OLD.remote_close_requested_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote close request time is immutable';
    END IF;

    IF OLD.remote_close_receipt_sha256 IS NOT NULL
            AND NEW.remote_close_receipt_sha256 IS DISTINCT FROM OLD.remote_close_receipt_sha256 THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote close receipt is immutable';
    END IF;

    IF OLD.remote_close_released_at IS NOT NULL
            AND NEW.remote_close_released_at IS DISTINCT FROM OLD.remote_close_released_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote close release time is immutable';
    END IF;

    IF NEW.remote_close_state IS DISTINCT FROM OLD.remote_close_state
            AND NOT (
                (OLD.remote_close_state IN ('NOT_STARTED', 'UNVERIFIED_LEGACY')
                    AND NEW.remote_close_state = 'REQUESTED')
                OR (OLD.remote_close_state = 'REQUESTED'
                    AND NEW.remote_close_state IN ('RECONCILING', 'BLOCKED', 'RELEASED'))
                OR (OLD.remote_close_state = 'RECONCILING'
                    AND NEW.remote_close_state IN ('BLOCKED', 'RELEASED'))
                OR (OLD.remote_close_state = 'BLOCKED'
                    AND NEW.remote_close_state IN ('RECONCILING', 'RELEASED'))
            ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote close state cannot move backwards or skip its durable start';
    END IF;

    IF (
            NEW.remote_close_state IS DISTINCT FROM OLD.remote_close_state
            OR NEW.remote_close_operation_id IS DISTINCT FROM OLD.remote_close_operation_id
            OR NEW.remote_close_receipt_sha256 IS DISTINCT FROM OLD.remote_close_receipt_sha256
            OR NEW.remote_close_error_code IS DISTINCT FROM OLD.remote_close_error_code
            OR NEW.remote_close_requested_at IS DISTINCT FROM OLD.remote_close_requested_at
            OR NEW.remote_close_updated_at IS DISTINCT FROM OLD.remote_close_updated_at
            OR NEW.remote_close_released_at IS DISTINCT FROM OLD.remote_close_released_at
        ) AND NEW.remote_close_revision <= OLD.remote_close_revision THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote close projection changes require a higher revision';
    END IF;

    IF OLD.remote_close_updated_at IS NOT NULL
            AND NEW.remote_close_updated_at IS NOT NULL
            AND NEW.remote_close_updated_at < OLD.remote_close_updated_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'remote close update time cannot move backwards';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_work_session_remote_close_monotonicity
BEFORE UPDATE OF
    remote_close_state,
    remote_close_operation_id,
    remote_close_revision,
    remote_close_receipt_sha256,
    remote_close_error_code,
    remote_close_requested_at,
    remote_close_updated_at,
    remote_close_released_at
ON work_session
FOR EACH ROW
EXECUTE FUNCTION enforce_work_session_remote_close_monotonicity();

ALTER TABLE agent_run
    ADD COLUMN failure_code VARCHAR(80),
    ADD COLUMN recovery_next_action VARCHAR(40),
    ADD CONSTRAINT ck_agent_run_failure_projection
        CHECK (
            (failure_code IS NULL AND recovery_next_action IS NULL)
            OR (failure_code IS NOT NULL
                AND failure_code ~ '^[A-Z][A-Z0-9_]{2,79}$'
                AND recovery_next_action IS NOT NULL
                AND status = 'FAILED')
        ),
    ADD CONSTRAINT ck_agent_run_recovery_next_action
        CHECK (recovery_next_action IS NULL OR recovery_next_action IN (
            'NONE', 'WAIT', 'RETRY', 'REQUEST_RECONCILIATION',
            'RECONCILE_REMOTE_CLOSE', 'CONTACT_PRIVILEGED_OPERATOR',
            'CONTACT_PLATFORM_ADMINISTRATOR'
        )),
    ADD CONSTRAINT ck_agent_run_closed_owner_recovery
        CHECK (
            failure_code IS DISTINCT FROM 'CLOSED_SESSION_OWNS_CAPACITY'
            OR recovery_next_action = 'RECONCILE_REMOTE_CLOSE'
        );

CREATE INDEX idx_agent_run_failure_recovery
    ON agent_run (failure_code, recovery_next_action, created_at)
    WHERE failure_code IS NOT NULL;

ALTER TABLE agent_run_recovery_operation
    DROP CONSTRAINT ck_agent_run_recovery_next_action,
    ADD CONSTRAINT ck_agent_run_recovery_next_action
        CHECK (required_next_action IS NULL OR required_next_action IN (
            'NONE', 'WAIT', 'RETRY', 'REQUEST_RECONCILIATION',
            'RECONCILE_REMOTE_CLOSE', 'CONTACT_PRIVILEGED_OPERATOR',
            'CONTACT_PLATFORM_ADMINISTRATOR'
        ));

CREATE TABLE remote_close_legacy_plan (
    id BIGSERIAL PRIMARY KEY,
    plan_id UUID NOT NULL,
    work_session_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    operation VARCHAR(40) NOT NULL,
    worker_id VARCHAR(80) NOT NULL,
    project_identity VARCHAR(80) NOT NULL,
    remote_session_id UUID NOT NULL,
    workspace_identity VARCHAR(200) NOT NULL,
    ownership_fingerprint_sha256 VARCHAR(64) NOT NULL,
    request_fingerprint_sha256 VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_remote_close_legacy_plan_id UNIQUE (plan_id),
    CONSTRAINT uk_remote_close_legacy_plan_idempotency
        UNIQUE (requested_by, idempotency_key),
    CONSTRAINT uk_remote_close_legacy_plan_binding
        UNIQUE (plan_id, work_session_id, ownership_fingerprint_sha256),
    CONSTRAINT fk_remote_close_legacy_plan_session
        FOREIGN KEY (work_session_id) REFERENCES work_session (id) ON DELETE RESTRICT,
    CONSTRAINT fk_remote_close_legacy_plan_operator
        FOREIGN KEY (requested_by) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_remote_close_legacy_plan_operation
        CHECK (operation = 'RECONCILE_REMOTE_CLOSE'),
    CONSTRAINT ck_remote_close_legacy_plan_ownership_fingerprint
        CHECK (ownership_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_remote_close_legacy_plan_request_fingerprint
        CHECK (request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_remote_close_legacy_plan_expiry
        CHECK (expires_at > created_at)
);

CREATE TABLE remote_close_legacy_operation (
    id BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    work_session_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    operation VARCHAR(40) NOT NULL,
    ownership_fingerprint_sha256 VARCHAR(64) NOT NULL,
    request_fingerprint_sha256 VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_remote_close_legacy_operation_id UNIQUE (operation_id),
    CONSTRAINT uk_remote_close_legacy_operation_plan UNIQUE (plan_id),
    CONSTRAINT uk_remote_close_legacy_operation_idempotency
        UNIQUE (requested_by, idempotency_key),
    CONSTRAINT fk_remote_close_legacy_operation_plan_binding
        FOREIGN KEY (plan_id, work_session_id, ownership_fingerprint_sha256)
        REFERENCES remote_close_legacy_plan (
            plan_id, work_session_id, ownership_fingerprint_sha256
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_remote_close_legacy_operation_operator
        FOREIGN KEY (requested_by) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_remote_close_legacy_operation_name
        CHECK (operation = 'RECONCILE_REMOTE_CLOSE'),
    CONSTRAINT ck_remote_close_legacy_operation_ownership_fingerprint
        CHECK (ownership_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_remote_close_legacy_operation_request_fingerprint
        CHECK (request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_remote_close_legacy_operation_state
        CHECK (state = 'REQUESTED'),
    CONSTRAINT ck_remote_close_legacy_operation_timestamps
        CHECK (created_at = requested_at)
);

CREATE INDEX idx_remote_close_legacy_plan_session
    ON remote_close_legacy_plan (work_session_id, created_at DESC);

CREATE INDEX idx_remote_close_legacy_operation_session
    ON remote_close_legacy_operation (work_session_id, created_at DESC);
