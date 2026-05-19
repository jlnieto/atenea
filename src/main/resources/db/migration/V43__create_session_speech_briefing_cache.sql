CREATE TABLE session_speech_briefing_cache (
    id BIGSERIAL PRIMARY KEY,
    work_session_id BIGINT NOT NULL,
    mode VARCHAR(20) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(120) NOT NULL,
    prompt_version VARCHAR(120) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    source_turn_id BIGINT,
    latest_run_id BIGINT,
    text TEXT NOT NULL,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_session_speech_briefing_cache_source
        UNIQUE (work_session_id, mode, provider, model, prompt_version, source_hash),
    CONSTRAINT ck_session_speech_briefing_cache_mode
        CHECK (mode IN ('BRIEF', 'FULL')),
    CONSTRAINT ck_session_speech_briefing_cache_text_not_blank
        CHECK (length(trim(text)) > 0)
);

CREATE INDEX idx_session_speech_briefing_cache_session
    ON session_speech_briefing_cache (work_session_id, created_at DESC);

CREATE INDEX idx_session_speech_briefing_cache_source_turn
    ON session_speech_briefing_cache (source_turn_id);

CREATE INDEX idx_session_speech_briefing_cache_latest_run
    ON session_speech_briefing_cache (latest_run_id);
