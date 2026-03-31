CREATE TABLE core_operator_context (
    operator_key VARCHAR(120) PRIMARY KEY,
    active_project_id BIGINT,
    active_work_session_id BIGINT,
    active_command_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_core_operator_context_project
        FOREIGN KEY (active_project_id) REFERENCES project (id) ON DELETE SET NULL,
    CONSTRAINT fk_core_operator_context_work_session
        FOREIGN KEY (active_work_session_id) REFERENCES work_session (id) ON DELETE SET NULL,
    CONSTRAINT fk_core_operator_context_core_command
        FOREIGN KEY (active_command_id) REFERENCES core_command (id) ON DELETE SET NULL
);

CREATE INDEX idx_core_operator_context_project
    ON core_operator_context (active_project_id);

CREATE INDEX idx_core_operator_context_work_session
    ON core_operator_context (active_work_session_id);
