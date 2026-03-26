CREATE TABLE work_session (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    base_branch VARCHAR(120) NOT NULL,
    workspace_branch VARCHAR(180),
    external_thread_id VARCHAR(100),
    opened_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_work_session_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT ck_work_session_status
        CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_work_session_closed_at_consistency
        CHECK (
            (status = 'OPEN' AND closed_at IS NULL)
            OR (status = 'CLOSED' AND closed_at IS NOT NULL)
        )
);

CREATE INDEX idx_work_session_project_id ON work_session (project_id);
CREATE INDEX idx_work_session_status ON work_session (status);
CREATE INDEX idx_work_session_last_activity_at_desc ON work_session (last_activity_at DESC);
CREATE UNIQUE INDEX uk_work_session_open_project ON work_session (project_id) WHERE status = 'OPEN';
