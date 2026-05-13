CREATE TABLE operator_session_read_state (
    id BIGSERIAL PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    work_session_id BIGINT NOT NULL,
    last_seen_activity_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_operator_session_read_state_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_operator_session_read_state_work_session
        FOREIGN KEY (work_session_id) REFERENCES work_session (id) ON DELETE CASCADE,
    CONSTRAINT uk_operator_session_read_state_operator_session
        UNIQUE (operator_id, work_session_id)
);

CREATE INDEX idx_operator_session_read_state_operator
    ON operator_session_read_state (operator_id);

CREATE INDEX idx_operator_session_read_state_work_session
    ON operator_session_read_state (work_session_id);
