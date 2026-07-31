ALTER TABLE operator_account
    ADD COLUMN codex_operations_role VARCHAR(32) NOT NULL DEFAULT 'ROUTINE_OPERATOR',
    ADD CONSTRAINT ck_operator_codex_operations_role
        CHECK (codex_operations_role IN (
            'ROUTINE_OPERATOR', 'PRIVILEGED_OPERATOR', 'PLATFORM_ADMINISTRATOR'
        ));

ALTER TABLE agent_run
    ADD COLUMN retry_of_run_id BIGINT,
    ADD CONSTRAINT fk_agent_run_retry_of
        FOREIGN KEY (retry_of_run_id) REFERENCES agent_run (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_agent_run_retry_not_self
        CHECK (retry_of_run_id IS NULL OR retry_of_run_id <> id),
    ADD CONSTRAINT uk_agent_run_retry_of
        UNIQUE (retry_of_run_id),
    ADD CONSTRAINT uk_agent_run_id_session
        UNIQUE (id, session_id);

CREATE TABLE agent_run_recovery_operation (
    id BIGSERIAL PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    idempotency_key UUID NOT NULL,
    request_fingerprint_sha256 VARCHAR(64) NOT NULL,
    operator_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    agent_run_id BIGINT NOT NULL,
    requested_role VARCHAR(32) NOT NULL,
    action VARCHAR(40) NOT NULL,
    state VARCHAR(16) NOT NULL,
    outcome_code VARCHAR(40),
    outcome_summary VARCHAR(240),
    required_next_action VARCHAR(40),
    result_agent_run_id BIGINT,
    requested_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_run_recovery_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_recovery_session
        FOREIGN KEY (session_id) REFERENCES work_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_run_recovery_owned_run
        FOREIGN KEY (agent_run_id, session_id)
        REFERENCES agent_run (id, session_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_run_recovery_result_run
        FOREIGN KEY (result_agent_run_id, session_id)
        REFERENCES agent_run (id, session_id) ON DELETE RESTRICT,
    CONSTRAINT uk_agent_run_recovery_idempotency
        UNIQUE (operator_id, idempotency_key),
    CONSTRAINT ck_agent_run_recovery_fingerprint
        CHECK (request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_run_recovery_role
        CHECK (requested_role IN (
            'ROUTINE_OPERATOR', 'PRIVILEGED_OPERATOR', 'PLATFORM_ADMINISTRATOR'
        )),
    CONSTRAINT ck_agent_run_recovery_action
        CHECK (action IN (
            'CANCEL', 'RETRY', 'RECONCILE', 'DIAGNOSTIC',
            'RESTART_EXECUTION_SERVICE', 'RESTART_PROJECT_APP_SERVER'
        )),
    CONSTRAINT ck_agent_run_recovery_role_action
        CHECK (
            action IN ('CANCEL', 'RETRY', 'RECONCILE', 'DIAGNOSTIC')
            OR requested_role IN ('PRIVILEGED_OPERATOR', 'PLATFORM_ADMINISTRATOR')
            OR (state = 'REJECTED' AND outcome_code = 'ROLE_REQUIRED')
        ),
    CONSTRAINT ck_agent_run_recovery_state
        CHECK (state IN ('REQUESTED', 'IN_PROGRESS', 'SUCCEEDED', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_agent_run_recovery_outcome
        CHECK (outcome_code IS NULL OR outcome_code IN (
            'CANCELLED', 'RETRY_CREATED', 'RECONCILED', 'DIAGNOSTIC_READY',
            'SERVICE_RESTARTED', 'OWNERSHIP_MISMATCH', 'ROLE_REQUIRED',
            'NOT_TERMINAL', 'NON_TERMINAL_RUN_EXISTS', 'WORKER_UNREACHABLE',
            'POLICY_BLOCKED', 'EXECUTION_STILL_LIVE', 'NO_CHANGE',
            'OPERATION_FAILED'
        )),
    CONSTRAINT ck_agent_run_recovery_next_action
        CHECK (required_next_action IS NULL OR required_next_action IN (
            'NONE', 'WAIT', 'RETRY', 'REQUEST_RECONCILIATION',
            'CONTACT_PRIVILEGED_OPERATOR', 'CONTACT_PLATFORM_ADMINISTRATOR'
        )),
    CONSTRAINT ck_agent_run_recovery_lifecycle
        CHECK (
            (state = 'REQUESTED'
                AND started_at IS NULL AND completed_at IS NULL
                AND outcome_code IS NULL AND outcome_summary IS NULL
                AND required_next_action IS NULL AND result_agent_run_id IS NULL)
            OR (state = 'IN_PROGRESS'
                AND started_at IS NOT NULL AND completed_at IS NULL
                AND outcome_code IS NULL AND outcome_summary IS NULL
                AND required_next_action IS NULL AND result_agent_run_id IS NULL)
            OR (state IN ('SUCCEEDED', 'REJECTED', 'FAILED')
                AND started_at IS NOT NULL AND completed_at IS NOT NULL
                AND outcome_code IS NOT NULL
                AND length(outcome_summary) BETWEEN 1 AND 240
                AND required_next_action IS NOT NULL)
        ),
    CONSTRAINT ck_agent_run_recovery_terminal_state_outcome
        CHECK (
            state IN ('REQUESTED', 'IN_PROGRESS')
            OR (state = 'SUCCEEDED' AND outcome_code IN (
                'CANCELLED', 'RETRY_CREATED', 'RECONCILED', 'DIAGNOSTIC_READY',
                'SERVICE_RESTARTED', 'NO_CHANGE'
            ))
            OR (state = 'REJECTED' AND outcome_code IN (
                'OWNERSHIP_MISMATCH', 'ROLE_REQUIRED', 'NOT_TERMINAL',
                'NON_TERMINAL_RUN_EXISTS', 'WORKER_UNREACHABLE',
                'POLICY_BLOCKED', 'EXECUTION_STILL_LIVE'
            ))
            OR (state = 'FAILED' AND outcome_code = 'OPERATION_FAILED')
        ),
    CONSTRAINT ck_agent_run_recovery_outcome_projection
        CHECK (
            (outcome_code IS NULL AND outcome_summary IS NULL
                AND required_next_action IS NULL)
            OR (outcome_code = 'CANCELLED' AND outcome_summary = 'Ejecución cancelada'
                AND required_next_action = 'NONE')
            OR (outcome_code = 'RETRY_CREATED' AND outcome_summary = 'Reintento creado'
                AND required_next_action = 'WAIT')
            OR (outcome_code = 'RECONCILED' AND outcome_summary = 'Estado reconciliado'
                AND required_next_action = 'NONE')
            OR (outcome_code = 'DIAGNOSTIC_READY' AND outcome_summary = 'Diagnóstico disponible'
                AND required_next_action = 'NONE')
            OR (outcome_code = 'SERVICE_RESTARTED' AND outcome_summary = 'Servicio mediado reiniciado'
                AND required_next_action = 'REQUEST_RECONCILIATION')
            OR (outcome_code = 'OWNERSHIP_MISMATCH' AND outcome_summary = 'La identidad no pertenece a esta sesión'
                AND required_next_action = 'NONE')
            OR (outcome_code = 'ROLE_REQUIRED' AND outcome_summary = 'Se necesita un operador privilegiado'
                AND required_next_action = 'CONTACT_PRIVILEGED_OPERATOR')
            OR (outcome_code = 'NOT_TERMINAL' AND outcome_summary = 'La ejecución anterior aún no es terminal'
                AND required_next_action = 'REQUEST_RECONCILIATION')
            OR (outcome_code = 'NON_TERMINAL_RUN_EXISTS' AND outcome_summary = 'La sesión ya tiene una ejecución activa'
                AND required_next_action = 'WAIT')
            OR (outcome_code = 'WORKER_UNREACHABLE' AND outcome_summary = 'El worker no está accesible'
                AND required_next_action = 'REQUEST_RECONCILIATION')
            OR (outcome_code = 'POLICY_BLOCKED' AND outcome_summary = 'La política no permite esta operación'
                AND required_next_action = 'CONTACT_PLATFORM_ADMINISTRATOR')
            OR (outcome_code = 'EXECUTION_STILL_LIVE' AND outcome_summary = 'La ejecución anterior sigue activa'
                AND required_next_action = 'REQUEST_RECONCILIATION')
            OR (outcome_code = 'NO_CHANGE' AND outcome_summary = 'El estado ya era el solicitado'
                AND required_next_action = 'NONE')
            OR (outcome_code = 'OPERATION_FAILED' AND outcome_summary = 'La operación no pudo completarse'
                AND required_next_action = 'RETRY')
        ),
    CONSTRAINT ck_agent_run_recovery_retry_result
        CHECK (
            (outcome_code = 'RETRY_CREATED' AND action = 'RETRY'
                AND result_agent_run_id IS NOT NULL)
            OR (outcome_code IS DISTINCT FROM 'RETRY_CREATED'
                AND result_agent_run_id IS NULL)
        )
);

CREATE INDEX idx_agent_run_recovery_run_requested
    ON agent_run_recovery_operation (agent_run_id, requested_at DESC);
CREATE INDEX idx_agent_run_recovery_session_requested
    ON agent_run_recovery_operation (session_id, requested_at DESC);
