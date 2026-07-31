ALTER TABLE agent_run
    ADD COLUMN progress_next_sequence BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN worker_progress_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN progress_retained_floor BIGINT,
    ADD COLUMN progress_latest_sequence BIGINT,
    ADD COLUMN progress_latest_category VARCHAR(32),
    ADD COLUMN progress_latest_message VARCHAR(160),
    ADD COLUMN progress_latest_at TIMESTAMPTZ,
    ADD COLUMN progress_current_state VARCHAR(32),
    ADD COLUMN progress_terminal_category VARCHAR(32),
    ADD COLUMN progress_elapsed_millis BIGINT,
    ADD COLUMN progress_required_next_action VARCHAR(32),
    ADD CONSTRAINT ck_agent_run_progress_next_sequence
        CHECK (progress_next_sequence >= 1),
    ADD CONSTRAINT ck_agent_run_worker_progress_sequence
        CHECK (worker_progress_sequence >= 0),
    ADD CONSTRAINT ck_agent_run_progress_projection
        CHECK (
            (progress_latest_sequence IS NULL
                AND progress_retained_floor IS NULL
                AND progress_latest_category IS NULL
                AND progress_latest_message IS NULL
                AND progress_latest_at IS NULL
                AND progress_current_state IS NULL
                AND progress_elapsed_millis IS NULL
                AND progress_required_next_action IS NULL
                AND progress_next_sequence = 1)
            OR (progress_latest_sequence >= 1
                AND progress_retained_floor BETWEEN 1 AND progress_latest_sequence
                AND progress_next_sequence > progress_latest_sequence
                AND progress_latest_category IS NOT NULL
                AND length(progress_latest_message) BETWEEN 1 AND 160
                AND progress_latest_at IS NOT NULL
                AND progress_current_state IS NOT NULL
                AND progress_elapsed_millis >= 0
                AND progress_required_next_action IS NOT NULL)
        ),
    ADD CONSTRAINT ck_agent_run_progress_category
        CHECK (progress_latest_category IS NULL OR progress_latest_category IN (
            'ACCEPTED', 'QUEUED', 'PREPARING_WORKSPACE', 'CODEX_STARTED',
            'INSPECTING_PROJECT', 'RUNNING_COMMAND', 'CHECKING', 'WAITING',
            'RECONCILING', 'FINALIZING', 'COMPLETED', 'FAILED', 'CANCELLED'
        )),
    ADD CONSTRAINT ck_agent_run_progress_current_state
        CHECK (progress_current_state IS NULL OR progress_current_state IN (
            'ACCEPTED', 'QUEUED', 'PREPARING_WORKSPACE', 'CODEX_STARTED',
            'INSPECTING_PROJECT', 'RUNNING_COMMAND', 'CHECKING', 'WAITING',
            'RECONCILING', 'FINALIZING', 'COMPLETED', 'FAILED', 'CANCELLED'
        )),
    ADD CONSTRAINT ck_agent_run_progress_next_action
        CHECK (progress_required_next_action IS NULL OR progress_required_next_action IN (
            'NONE', 'WAIT', 'CANCEL', 'RETRY', 'REQUEST_RECONCILIATION'
        )),
    ADD CONSTRAINT ck_agent_run_progress_terminal
        CHECK (
            (progress_latest_category IS NULL AND progress_terminal_category IS NULL)
            OR (progress_latest_category NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                AND progress_terminal_category IS NULL)
            OR (progress_latest_category = 'COMPLETED'
                AND progress_current_state = 'COMPLETED'
                AND progress_terminal_category = 'COMPLETED'
                AND status = 'SUCCEEDED')
            OR (progress_latest_category = 'FAILED'
                AND progress_current_state = 'FAILED'
                AND progress_terminal_category = 'FAILED'
                AND status = 'FAILED')
            OR (progress_latest_category = 'CANCELLED'
                AND progress_current_state = 'CANCELLED'
                AND progress_terminal_category = 'CANCELLED'
                AND status = 'CANCELLED')
        ),
    ADD CONSTRAINT ck_agent_run_progress_latest_message
        CHECK (
            progress_latest_category IS NULL
            OR (progress_latest_category = 'ACCEPTED' AND progress_latest_message = 'Solicitud aceptada')
            OR (progress_latest_category = 'QUEUED' AND progress_latest_message = 'Ejecución en cola')
            OR (progress_latest_category = 'PREPARING_WORKSPACE' AND progress_latest_message = 'Preparando el espacio de trabajo')
            OR (progress_latest_category = 'CODEX_STARTED' AND progress_latest_message = 'Codex iniciado')
            OR (progress_latest_category = 'INSPECTING_PROJECT' AND progress_latest_message = 'Revisando el proyecto')
            OR (progress_latest_category = 'RUNNING_COMMAND' AND progress_latest_message = 'Ejecutando una operación permitida')
            OR (progress_latest_category = 'CHECKING' AND progress_latest_message = 'Comprobando el resultado')
            OR (progress_latest_category = 'WAITING' AND progress_latest_message = 'Esperando una condición necesaria')
            OR (progress_latest_category = 'RECONCILING' AND progress_latest_message = 'Reconciliando el estado')
            OR (progress_latest_category = 'FINALIZING' AND progress_latest_message = 'Finalizando')
            OR (progress_latest_category = 'COMPLETED' AND progress_latest_message = 'Tarea completada')
            OR (progress_latest_category = 'FAILED' AND progress_latest_message = 'La tarea necesita atención')
            OR (progress_latest_category = 'CANCELLED' AND progress_latest_message = 'Tarea cancelada')
        );

CREATE TABLE agent_run_progress_event (
    id BIGSERIAL PRIMARY KEY,
    agent_run_id BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    operator_message VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_run_progress_event_run
        FOREIGN KEY (agent_run_id) REFERENCES agent_run (id) ON DELETE CASCADE,
    CONSTRAINT uk_agent_run_progress_event_sequence
        UNIQUE (agent_run_id, sequence),
    CONSTRAINT ck_agent_run_progress_event_sequence
        CHECK (sequence >= 1),
    CONSTRAINT ck_agent_run_progress_event_message
        CHECK (
            (category = 'ACCEPTED' AND operator_message = 'Solicitud aceptada')
            OR (category = 'QUEUED' AND operator_message = 'Ejecución en cola')
            OR (category = 'PREPARING_WORKSPACE' AND operator_message = 'Preparando el espacio de trabajo')
            OR (category = 'CODEX_STARTED' AND operator_message = 'Codex iniciado')
            OR (category = 'INSPECTING_PROJECT' AND operator_message = 'Revisando el proyecto')
            OR (category = 'RUNNING_COMMAND' AND operator_message = 'Ejecutando una operación permitida')
            OR (category = 'CHECKING' AND operator_message = 'Comprobando el resultado')
            OR (category = 'WAITING' AND operator_message = 'Esperando una condición necesaria')
            OR (category = 'RECONCILING' AND operator_message = 'Reconciliando el estado')
            OR (category = 'FINALIZING' AND operator_message = 'Finalizando')
            OR (category = 'COMPLETED' AND operator_message = 'Tarea completada')
            OR (category = 'FAILED' AND operator_message = 'La tarea necesita atención')
            OR (category = 'CANCELLED' AND operator_message = 'Tarea cancelada')
        ),
    CONSTRAINT ck_agent_run_progress_event_category
        CHECK (category IN (
            'ACCEPTED', 'QUEUED', 'PREPARING_WORKSPACE', 'CODEX_STARTED',
            'INSPECTING_PROJECT', 'RUNNING_COMMAND', 'CHECKING', 'WAITING',
            'RECONCILING', 'FINALIZING', 'COMPLETED', 'FAILED', 'CANCELLED'
        ))
);

CREATE INDEX idx_agent_run_progress_event_replay
    ON agent_run_progress_event (agent_run_id, sequence);
