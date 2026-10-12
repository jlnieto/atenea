-- Empty-only contraction. The separately reviewed operator cleanup must run
-- before V94/V95. Never delete/adopt trial rows from application startup.
LOCK TABLE work_session, remote_close_legacy_plan, remote_close_legacy_operation,
    remote_close_legacy_event, fresh_work_session_operation, rescue_session,
    rescue_session_turn IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM remote_close_legacy_plan)
        OR EXISTS (SELECT 1 FROM remote_close_legacy_operation)
        OR EXISTS (SELECT 1 FROM remote_close_legacy_event)
        OR EXISTS (SELECT 1 FROM fresh_work_session_operation)
        OR EXISTS (SELECT 1 FROM rescue_session)
        OR EXISTS (SELECT 1 FROM rescue_session_turn)
        OR EXISTS (SELECT 1 FROM work_session WHERE fresh_start_operation_id IS NOT NULL
            OR replacement_work_session_id IS NOT NULL
            OR remote_close_state='UNVERIFIED_LEGACY') THEN
        RAISE EXCEPTION 'ATENEA_CHANGE_ONLY_LEGACY_DATA_REMAINS' USING ERRCODE='55000';
    END IF;
END;
$$;

-- The close guard retains all operation/revision/receipt/owner protections.
-- Only comparison of the now-absent successor marker is removed.
CREATE OR REPLACE FUNCTION enforce_work_session_remote_close_monotonicity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    exact_initial_remote_pin BOOLEAN;
BEGIN
    exact_initial_remote_pin := (
        OLD.status = 'OPEN'
        AND NEW.status = 'OPEN'
        AND OLD.closed_at IS NULL
        AND NEW.closed_at IS NULL
        AND OLD.execution_target = 'LOCAL'
        AND NEW.execution_target = 'REMOTE'
        AND OLD.selected_worker_id IS NULL
        AND NEW.selected_worker_id IS NOT NULL
        AND OLD.remote_session_id IS NULL
        AND NEW.remote_session_id IS NOT NULL
        AND OLD.remote_workload_kind IS NULL
        AND NEW.remote_workload_kind IS NOT NULL
        AND OLD.workspace_identity IN (
            'local:pending',
            'local:work-session:' || OLD.id::text
        )
        AND NEW.workspace_identity = 'remote:' || NEW.selected_worker_id
            || ':work-session:' || NEW.remote_session_id::text
        AND OLD.remote_close_state = 'NOT_REQUIRED'
        AND NEW.remote_close_state = 'NOT_STARTED'
        AND OLD.remote_close_revision = 0
        AND NEW.remote_close_revision = 0
        AND OLD.remote_close_operation_id IS NULL
        AND NEW.remote_close_operation_id IS NULL
        AND OLD.remote_close_receipt_sha256 IS NULL
        AND NEW.remote_close_receipt_sha256 IS NULL
        AND OLD.remote_close_error_code IS NULL
        AND NEW.remote_close_error_code IS NULL
        AND OLD.remote_close_requested_at IS NULL
        AND NEW.remote_close_requested_at IS NULL
        AND OLD.remote_close_updated_at IS NULL
        AND NEW.remote_close_updated_at IS NULL
        AND OLD.remote_close_released_at IS NULL
        AND NEW.remote_close_released_at IS NULL
        AND OLD.project_id = NEW.project_id
    );

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
                exact_initial_remote_pin
                OR (OLD.remote_close_state IN ('NOT_STARTED', 'UNVERIFIED_LEGACY')
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
        ) AND NEW.remote_close_revision <= OLD.remote_close_revision
          AND NOT exact_initial_remote_pin THEN
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

-- Explicit internal FK cycle only; no CASCADE over unrelated schema.
ALTER TABLE remote_close_legacy_plan
    DROP CONSTRAINT fk_remote_close_legacy_plan_consumed_operation;
DROP TABLE remote_close_legacy_event;
DROP TABLE remote_close_legacy_operation;
DROP TABLE remote_close_legacy_plan;
DROP TABLE fresh_work_session_operation;
DROP TABLE rescue_session_turn;
DROP TABLE rescue_session;
ALTER TABLE work_session DROP COLUMN fresh_start_operation_id;
ALTER TABLE work_session DROP COLUMN replacement_work_session_id;
ALTER TABLE work_session ADD CONSTRAINT ck_work_session_change_owned_close
    CHECK (remote_close_state <> 'UNVERIFIED_LEGACY');
