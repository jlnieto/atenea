CREATE TABLE core_command (
    id BIGSERIAL PRIMARY KEY,
    raw_input TEXT NOT NULL,
    channel VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    domain VARCHAR(32),
    intent VARCHAR(80),
    capability VARCHAR(80),
    risk_level VARCHAR(32),
    requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    confirmation_token VARCHAR(120),
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    confidence NUMERIC(5,4),
    request_context_json TEXT,
    parameters_json TEXT,
    interpreted_intent_json TEXT,
    result_type VARCHAR(64),
    target_type VARCHAR(64),
    target_id BIGINT,
    result_summary TEXT,
    error_code VARCHAR(64),
    error_message TEXT,
    operator_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT ck_core_command_channel
        CHECK (channel IN ('TEXT', 'VOICE')),
    CONSTRAINT ck_core_command_status
        CHECK (status IN ('RECEIVED', 'SUCCEEDED', 'NEEDS_CONFIRMATION', 'REJECTED', 'FAILED')),
    CONSTRAINT ck_core_command_domain
        CHECK (domain IS NULL OR domain IN ('DEVELOPMENT', 'OPERATIONS', 'COMMUNICATIONS')),
    CONSTRAINT ck_core_command_risk_level
        CHECK (risk_level IS NULL OR risk_level IN ('READ', 'SAFE_WRITE', 'DESTRUCTIVE')),
    CONSTRAINT ck_core_command_result_type
        CHECK (
            result_type IS NULL
            OR result_type IN ('WORK_SESSION', 'WORK_SESSION_VIEW', 'WORK_SESSION_CONVERSATION_VIEW')
        ),
    CONSTRAINT ck_core_command_target_type
        CHECK (
            target_type IS NULL
            OR target_type IN ('WORK_SESSION', 'SESSION_TURN')
        )
);

CREATE INDEX idx_core_command_created_at_desc
    ON core_command (created_at DESC);

CREATE INDEX idx_core_command_status_created_at_desc
    ON core_command (status, created_at DESC);

CREATE INDEX idx_core_command_domain_created_at_desc
    ON core_command (domain, created_at DESC);

CREATE INDEX idx_core_command_target_type_target_id
    ON core_command (target_type, target_id);
