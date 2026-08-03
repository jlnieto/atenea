CREATE TABLE work_session_preview (
    id UUID PRIMARY KEY,
    work_session_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    agent_run_id BIGINT,
    worker_id VARCHAR(80) NOT NULL,
    allocation_identity VARCHAR(200) NOT NULL,
    allocation_fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    lifecycle_revision BIGINT NOT NULL,
    localhost_compatible BOOLEAN NOT NULL DEFAULT FALSE,
    private_url VARCHAR(500),
    lease_expires_at TIMESTAMPTZ NOT NULL,
    hard_expires_at TIMESTAMPTZ NOT NULL,
    audit_retain_until TIMESTAMPTZ NOT NULL,
    failure_code VARCHAR(80),
    failure_reason VARCHAR(500),
    next_action VARCHAR(500),
    ready_at TIMESTAMPTZ,
    stopped_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_work_session_preview_session
        FOREIGN KEY (work_session_id) REFERENCES work_session (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_session_preview_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_session_preview_agent_run
        FOREIGN KEY (agent_run_id) REFERENCES agent_run (id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_session_preview_worker
        FOREIGN KEY (worker_id) REFERENCES worker_node (id) ON DELETE RESTRICT,
    CONSTRAINT ck_work_session_preview_state
        CHECK (state IN ('STOPPED', 'STARTING', 'READY', 'BLOCKED', 'RECONCILING', 'EXPIRED')),
    CONSTRAINT ck_work_session_preview_revision
        CHECK (lifecycle_revision >= 1),
    CONSTRAINT ck_work_session_preview_fingerprint
        CHECK (allocation_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_work_session_preview_expiry
        CHECK (
            lease_expires_at > created_at
            AND hard_expires_at > created_at
            AND lease_expires_at <= hard_expires_at
            AND audit_retain_until >= hard_expires_at
        ),
    CONSTRAINT ck_work_session_preview_ready_url
        CHECK (
            (state = 'READY' AND private_url IS NOT NULL)
            OR (state <> 'READY')
        )
);

CREATE UNIQUE INDEX uk_work_session_preview_active_session
    ON work_session_preview (work_session_id)
    WHERE state IN ('STARTING', 'READY', 'RECONCILING');

CREATE INDEX idx_work_session_preview_session_order
    ON work_session_preview (work_session_id, created_at DESC, id DESC);

CREATE INDEX idx_work_session_preview_reconciliation
    ON work_session_preview (state, lease_expires_at, hard_expires_at)
    WHERE state IN ('STARTING', 'READY', 'RECONCILING');

CREATE INDEX idx_work_session_preview_audit_retention
    ON work_session_preview (audit_retain_until);
