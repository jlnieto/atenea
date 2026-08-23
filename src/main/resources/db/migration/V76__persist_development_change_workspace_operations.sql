ALTER TABLE development_change
    ADD COLUMN observed_canonical_commit VARCHAR(64),
    ADD COLUMN workspace_state VARCHAR(24) NOT NULL DEFAULT 'NOT_PROVISIONED',
    ADD COLUMN workspace_operation_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN workspace_observation_sha256 VARCHAR(64),
    ADD COLUMN workspace_updated_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_development_change_observed_canonical_commit
        CHECK (observed_canonical_commit IS NULL
            OR observed_canonical_commit ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    ADD CONSTRAINT ck_development_change_workspace_state
        CHECK (workspace_state IN ('NOT_PROVISIONED', 'READY', 'UNCERTAIN', 'BLOCKED')),
    ADD CONSTRAINT ck_development_change_workspace_revision
        CHECK (workspace_operation_revision >= 0),
    ADD CONSTRAINT ck_development_change_workspace_observation
        CHECK (workspace_observation_sha256 IS NULL
            OR workspace_observation_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_development_change_workspace_projection
        CHECK (
            (workspace_operation_revision = 0
                AND workspace_state = 'NOT_PROVISIONED'
                AND workspace_observation_sha256 IS NULL
                AND workspace_updated_at IS NULL)
            OR
            (workspace_operation_revision > 0
                AND workspace_observation_sha256 IS NOT NULL
                AND workspace_updated_at IS NOT NULL)
        );

CREATE TABLE development_change_workspace_operation (
    id BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    operator_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    development_change_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    operation_kind VARCHAR(16) NOT NULL,
    predecessor_operation_id UUID,
    request_fingerprint_sha256 VARCHAR(64) NOT NULL,
    target_fingerprint_sha256 VARCHAR(64) NOT NULL,
    expected_source_revision BIGINT NOT NULL,
    expected_source_fingerprint_sha256 VARCHAR(64) NOT NULL,
    expected_canonical_commit VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    revision BIGINT NOT NULL DEFAULT 0,
    result_workspace_state VARCHAR(24),
    result_source_state VARCHAR(24),
    result_source_revision BIGINT,
    result_source_fingerprint_sha256 VARCHAR(64),
    observed_canonical_commit VARCHAR(64),
    failure_category VARCHAR(24),
    failure_code VARCHAR(80),
    receipt_sha256 VARCHAR(64),
    requested_at TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_development_change_workspace_operation_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_development_change_workspace_operation_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT fk_development_change_workspace_operation_change
        FOREIGN KEY (development_change_id, project_id)
        REFERENCES development_change (id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_development_change_workspace_operation_predecessor
        FOREIGN KEY (predecessor_operation_id)
        REFERENCES development_change_workspace_operation (operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT uk_development_change_workspace_idempotency
        UNIQUE (operation_kind, idempotency_key),
    CONSTRAINT ck_development_change_workspace_operation_kind
        CHECK (operation_kind IN ('PROVISION', 'INSPECT', 'RECONCILE')),
    CONSTRAINT ck_development_change_workspace_operation_predecessor
        CHECK ((operation_kind = 'RECONCILE' AND predecessor_operation_id IS NOT NULL)
            OR (operation_kind <> 'RECONCILE' AND predecessor_operation_id IS NULL)),
    CONSTRAINT ck_development_change_workspace_operation_fingerprints
        CHECK (request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
            AND target_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
            AND expected_source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_development_change_workspace_operation_expected
        CHECK (expected_source_revision >= 0
            AND expected_canonical_commit ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    CONSTRAINT ck_development_change_workspace_operation_state
        CHECK (state IN ('REQUESTED', 'DISPATCHED', 'SUCCEEDED', 'UNCERTAIN', 'BLOCKED')),
    CONSTRAINT ck_development_change_workspace_operation_result
        CHECK (
            (state = 'REQUESTED' AND revision = 0
                AND dispatched_at IS NULL AND completed_at IS NULL
                AND result_workspace_state IS NULL AND result_source_state IS NULL
                AND result_source_revision IS NULL
                AND result_source_fingerprint_sha256 IS NULL
                AND observed_canonical_commit IS NULL
                AND failure_category IS NULL AND failure_code IS NULL
                AND receipt_sha256 IS NULL)
            OR
            (state = 'DISPATCHED' AND revision = 1
                AND dispatched_at IS NOT NULL AND completed_at IS NULL
                AND result_workspace_state IS NULL AND result_source_state IS NULL
                AND result_source_revision IS NULL
                AND result_source_fingerprint_sha256 IS NULL
                AND observed_canonical_commit IS NULL
                AND failure_category IS NULL AND failure_code IS NULL
                AND receipt_sha256 IS NULL)
            OR
            (state IN ('SUCCEEDED', 'UNCERTAIN', 'BLOCKED') AND revision = 2
                AND dispatched_at IS NOT NULL AND completed_at IS NOT NULL
                AND result_workspace_state IS NOT NULL AND result_source_state IS NOT NULL
                AND result_source_revision IS NOT NULL
                AND result_source_fingerprint_sha256 IS NOT NULL
                AND receipt_sha256 IS NOT NULL
                AND ((state = 'SUCCEEDED'
                        AND failure_category IS NULL AND failure_code IS NULL)
                    OR (state = 'UNCERTAIN'
                        AND failure_category = 'TRANSPORT' AND failure_code IS NOT NULL)
                    OR (state = 'BLOCKED'
                        AND failure_category IS NOT NULL AND failure_code IS NOT NULL)))
        ),
    CONSTRAINT ck_development_change_workspace_operation_result_values
        CHECK ((result_workspace_state IS NULL
                OR result_workspace_state IN ('NOT_PROVISIONED', 'READY', 'UNCERTAIN', 'BLOCKED'))
            AND (result_source_state IS NULL
                OR result_source_state IN ('CLEAN', 'DIRTY', 'STALE', 'BLOCKED'))
            AND (result_source_revision IS NULL OR result_source_revision >= 0)
            AND (result_source_fingerprint_sha256 IS NULL
                OR result_source_fingerprint_sha256 ~ '^[0-9a-f]{64}$')
            AND (observed_canonical_commit IS NULL
                OR observed_canonical_commit ~ '^([0-9a-f]{40}|[0-9a-f]{64})$')
            AND (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{2,79}$')
            AND (receipt_sha256 IS NULL OR receipt_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_development_change_workspace_operation_timestamps
        CHECK (updated_at >= requested_at
            AND (dispatched_at IS NULL OR dispatched_at >= requested_at)
            AND (completed_at IS NULL OR completed_at >= requested_at))
);

CREATE UNIQUE INDEX uk_development_change_workspace_predecessor
    ON development_change_workspace_operation (predecessor_operation_id)
    WHERE predecessor_operation_id IS NOT NULL;

CREATE UNIQUE INDEX uk_development_change_workspace_active
    ON development_change_workspace_operation (development_change_id)
    WHERE state IN ('REQUESTED', 'DISPATCHED');

CREATE INDEX idx_development_change_workspace_uncertain
    ON development_change_workspace_operation (requested_at, development_change_id)
    WHERE state = 'UNCERTAIN';

CREATE FUNCTION validate_development_change_workspace_predecessor()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    predecessor_count BIGINT;
BEGIN
    IF NEW.operation_kind <> 'RECONCILE' THEN
        RETURN NEW;
    END IF;
    SELECT count(*) INTO predecessor_count
    FROM development_change_workspace_operation predecessor
    WHERE predecessor.operation_id = NEW.predecessor_operation_id
      AND predecessor.development_change_id = NEW.development_change_id
      AND predecessor.project_id = NEW.project_id
      AND predecessor.state = 'UNCERTAIN';
    IF predecessor_count <> 1 THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'workspace reconciliation predecessor is not exact and uncertain';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_development_change_workspace_predecessor
BEFORE INSERT ON development_change_workspace_operation
FOR EACH ROW
EXECUTE FUNCTION validate_development_change_workspace_predecessor();

CREATE FUNCTION reject_development_change_workspace_operation_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'development change workspace operations cannot be deleted';
    END IF;
    IF OLD.state IN ('SUCCEEDED', 'UNCERTAIN', 'BLOCKED') THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'terminal development change workspace operations are immutable';
    END IF;
    IF NEW.operation_id IS DISTINCT FROM OLD.operation_id
            OR NEW.operator_id IS DISTINCT FROM OLD.operator_id
            OR NEW.project_id IS DISTINCT FROM OLD.project_id
            OR NEW.development_change_id IS DISTINCT FROM OLD.development_change_id
            OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
            OR NEW.operation_kind IS DISTINCT FROM OLD.operation_kind
            OR NEW.predecessor_operation_id IS DISTINCT FROM OLD.predecessor_operation_id
            OR NEW.request_fingerprint_sha256 IS DISTINCT FROM OLD.request_fingerprint_sha256
            OR NEW.target_fingerprint_sha256 IS DISTINCT FROM OLD.target_fingerprint_sha256
            OR NEW.expected_source_revision IS DISTINCT FROM OLD.expected_source_revision
            OR NEW.expected_source_fingerprint_sha256 IS DISTINCT FROM OLD.expected_source_fingerprint_sha256
            OR NEW.expected_canonical_commit IS DISTINCT FROM OLD.expected_canonical_commit
            OR NEW.requested_at IS DISTINCT FROM OLD.requested_at THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'development change workspace operation identity is immutable';
    END IF;
    IF NEW.revision <> OLD.revision + 1
            OR (OLD.state = 'REQUESTED' AND NEW.state <> 'DISPATCHED')
            OR (OLD.state = 'DISPATCHED'
                AND NEW.state NOT IN ('SUCCEEDED', 'UNCERTAIN', 'BLOCKED')) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'development change workspace operation transition is invalid';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_development_change_workspace_operation_immutable
BEFORE UPDATE OR DELETE ON development_change_workspace_operation
FOR EACH ROW
EXECUTE FUNCTION reject_development_change_workspace_operation_mutation();
