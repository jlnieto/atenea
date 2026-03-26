CREATE TABLE session_turn (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    actor VARCHAR(16) NOT NULL,
    message_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_session_turn_session
        FOREIGN KEY (session_id) REFERENCES work_session (id) ON DELETE CASCADE,
    CONSTRAINT ck_session_turn_actor
        CHECK (actor IN ('OPERATOR', 'CODEX', 'ATENEA'))
);

CREATE INDEX idx_session_turn_session_id_created_at ON session_turn (session_id, created_at);
