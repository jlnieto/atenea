CREATE TABLE voice_command_telemetry (
    id BIGSERIAL PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    client_event_id VARCHAR(80),
    source VARCHAR(40) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    reason VARCHAR(120) NOT NULL,
    transcript TEXT NOT NULL,
    normalized_transcript TEXT,
    wake_word_detected BOOLEAN NOT NULL DEFAULT FALSE,
    starts_with_wake_word BOOLEAN NOT NULL DEFAULT FALSE,
    intent_type VARCHAR(120),
    domain VARCHAR(32),
    project_id BIGINT,
    project_name VARCHAR(160),
    work_session_id BIGINT,
    work_session_title VARCHAR(220),
    active_command_id BIGINT,
    active_note_count INTEGER,
    pending_send_intent_id BIGINT,
    realtime_connected BOOLEAN,
    voice_state VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_voice_command_telemetry_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE CASCADE,
    CONSTRAINT ck_voice_command_telemetry_outcome
        CHECK (outcome IN ('IGNORED', 'UNRECOGNIZED', 'BLOCKED', 'FAILED')),
    CONSTRAINT ck_voice_command_telemetry_reason_not_blank
        CHECK (length(trim(reason)) > 0),
    CONSTRAINT ck_voice_command_telemetry_transcript_not_blank
        CHECK (length(trim(transcript)) > 0)
);

CREATE INDEX idx_voice_command_telemetry_operator_created
    ON voice_command_telemetry (operator_id, created_at DESC);

CREATE INDEX idx_voice_command_telemetry_reason_created
    ON voice_command_telemetry (reason, created_at DESC);
