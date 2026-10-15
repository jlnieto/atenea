CREATE TABLE product_update_announcement (
    id UUID PRIMARY KEY,
    operator_id BIGINT NOT NULL REFERENCES operator_account(id) ON DELETE RESTRICT,
    source_commit VARCHAR(40) NOT NULL CHECK (source_commit ~ '^[0-9a-f]{40}$'),
    version_code INTEGER NOT NULL CHECK (version_code>0),
    version_name VARCHAR(80) NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('CLAIMED','ACCEPTED','UNCERTAIN')),
    device_count INTEGER NOT NULL CHECK (device_count>0),
    evidence_json JSONB NOT NULL CHECK (jsonb_typeof(evidence_json)='object'),
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    UNIQUE(operator_id,source_commit,version_code),
    CHECK ((state='CLAIMED' AND finished_at IS NULL) OR (state<>'CLAIMED' AND finished_at IS NOT NULL)),
    CHECK ((state='UNCERTAIN' AND error_code IS NOT NULL) OR (state<>'UNCERTAIN' AND error_code IS NULL))
);
