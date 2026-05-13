CREATE TABLE rescue_session (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    external_thread_id VARCHAR(100),
    external_turn_id VARCHAR(100),
    opened_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rescue_session_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT ck_rescue_session_status
        CHECK (status IN ('OPEN', 'RUNNING', 'CLOSED')),
    CONSTRAINT ck_rescue_session_closed_at_consistency
        CHECK (
            (status IN ('OPEN', 'RUNNING') AND closed_at IS NULL)
            OR (status = 'CLOSED' AND closed_at IS NOT NULL)
        )
);

CREATE INDEX idx_rescue_session_project_id ON rescue_session (project_id);
CREATE INDEX idx_rescue_session_status ON rescue_session (status);
CREATE INDEX idx_rescue_session_last_activity_at_desc ON rescue_session (last_activity_at DESC);
CREATE UNIQUE INDEX uk_rescue_session_active_project ON rescue_session (project_id) WHERE status IN ('OPEN', 'RUNNING');

CREATE TABLE rescue_session_turn (
    id BIGSERIAL PRIMARY KEY,
    rescue_session_id BIGINT NOT NULL,
    actor VARCHAR(16) NOT NULL,
    message_text TEXT NOT NULL,
    external_turn_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rescue_session_turn_session
        FOREIGN KEY (rescue_session_id) REFERENCES rescue_session (id) ON DELETE CASCADE,
    CONSTRAINT ck_rescue_session_turn_actor
        CHECK (actor IN ('OPERATOR', 'CODEX', 'ATENEA'))
);

CREATE INDEX idx_rescue_session_turn_session_id_created_at
    ON rescue_session_turn (rescue_session_id, created_at);
