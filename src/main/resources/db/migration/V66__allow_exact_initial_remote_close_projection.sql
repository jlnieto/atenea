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
        AND OLD.fresh_start_operation_id IS NOT DISTINCT FROM
            NEW.fresh_start_operation_id
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
