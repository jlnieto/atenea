CREATE TABLE voice_focus (
    operator_id BIGINT PRIMARY KEY,
    domain VARCHAR(32) NOT NULL DEFAULT 'NONE',
    project_id BIGINT,
    work_session_id BIGINT,
    active_command_id BIGINT,
    managed_host_id BIGINT,
    activity VARCHAR(80),
    playback_source_type VARCHAR(80),
    playback_source_id VARCHAR(120),
    playback_segment_index INTEGER,
    playback_segment_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_voice_focus_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_voice_focus_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE SET NULL,
    CONSTRAINT fk_voice_focus_work_session
        FOREIGN KEY (work_session_id) REFERENCES work_session (id) ON DELETE SET NULL,
    CONSTRAINT fk_voice_focus_core_command
        FOREIGN KEY (active_command_id) REFERENCES core_command (id) ON DELETE SET NULL,
    CONSTRAINT fk_voice_focus_managed_host
        FOREIGN KEY (managed_host_id) REFERENCES managed_host (id) ON DELETE SET NULL,
    CONSTRAINT ck_voice_focus_domain
        CHECK (domain IN ('NONE', 'DEVELOPMENT', 'OPERATIONS', 'COMMUNICATIONS', 'PERSONAL')),
    CONSTRAINT ck_voice_focus_playback_index
        CHECK (playback_segment_index IS NULL OR playback_segment_index >= 0),
    CONSTRAINT ck_voice_focus_playback_count
        CHECK (playback_segment_count IS NULL OR playback_segment_count >= 0)
);

CREATE INDEX idx_voice_focus_project
    ON voice_focus (project_id);

CREATE INDEX idx_voice_focus_work_session
    ON voice_focus (work_session_id);

CREATE TABLE voice_note (
    id BIGSERIAL PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    text TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    focus_snapshot_json TEXT,
    consumed_by_command_id BIGINT,
    captured_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_voice_note_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_voice_note_consumed_command
        FOREIGN KEY (consumed_by_command_id) REFERENCES core_command (id) ON DELETE SET NULL,
    CONSTRAINT ck_voice_note_status
        CHECK (status IN ('ACTIVE', 'SENT', 'ARCHIVED')),
    CONSTRAINT ck_voice_note_text_not_blank
        CHECK (length(trim(text)) > 0)
);

CREATE INDEX idx_voice_note_operator_status_created
    ON voice_note (operator_id, status, created_at DESC);

CREATE INDEX idx_voice_note_consumed_command
    ON voice_note (consumed_by_command_id);
