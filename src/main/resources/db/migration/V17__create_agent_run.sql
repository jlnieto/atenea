CREATE TABLE agent_run (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    origin_turn_id BIGINT NOT NULL,
    result_turn_id BIGINT,
    status VARCHAR(32) NOT NULL,
    target_repo_path VARCHAR(500) NOT NULL,
    external_turn_id VARCHAR(100),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    output_summary TEXT,
    error_summary TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_run_session
        FOREIGN KEY (session_id) REFERENCES work_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_run_origin_turn
        FOREIGN KEY (origin_turn_id) REFERENCES session_turn (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_result_turn
        FOREIGN KEY (result_turn_id) REFERENCES session_turn (id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_run_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_agent_run_finished_at_consistency
        CHECK (
            (status = 'RUNNING' AND finished_at IS NULL)
            OR (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND finished_at IS NOT NULL)
        )
);

CREATE INDEX idx_agent_run_session_id_created_at_desc ON agent_run (session_id, created_at DESC);
CREATE INDEX idx_agent_run_session_id_status ON agent_run (session_id, status);
CREATE INDEX idx_agent_run_origin_turn_id ON agent_run (origin_turn_id);
CREATE UNIQUE INDEX uk_agent_run_running_session ON agent_run (session_id) WHERE status = 'RUNNING';
