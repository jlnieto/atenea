CREATE TABLE api_usage_record (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(120) NOT NULL,
    feature VARCHAR(80) NOT NULL,
    environment VARCHAR(40),
    status VARCHAR(40) NOT NULL,
    project_id BIGINT,
    work_session_id BIGINT,
    agent_run_id BIGINT,
    session_turn_id BIGINT,
    core_command_id BIGINT,
    provider_request_id VARCHAR(160),
    currency VARCHAR(12) NOT NULL DEFAULT 'usd',
    estimated_cost NUMERIC(18, 8) NOT NULL DEFAULT 0,
    input_tokens BIGINT,
    input_cache_hit_tokens BIGINT,
    input_cache_miss_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    audio_input_seconds NUMERIC(12, 3),
    audio_output_seconds NUMERIC(12, 3),
    request_count INTEGER NOT NULL DEFAULT 1,
    metadata_json TEXT,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_usage_record_provider_started ON api_usage_record(provider, started_at);
CREATE INDEX idx_api_usage_record_provider_model_started ON api_usage_record(provider, model, started_at);
CREATE INDEX idx_api_usage_record_feature_started ON api_usage_record(feature, started_at);
CREATE INDEX idx_api_usage_record_project_started ON api_usage_record(project_id, started_at);
CREATE INDEX idx_api_usage_record_work_session_started ON api_usage_record(work_session_id, started_at);
