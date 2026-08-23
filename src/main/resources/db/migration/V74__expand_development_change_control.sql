CREATE TABLE development_change (
    id BIGSERIAL PRIMARY KEY,
    change_key UUID NOT NULL,
    project_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    base_ref VARCHAR(220) NOT NULL,
    base_commit VARCHAR(64) NOT NULL,
    workspace_branch VARCHAR(180) NOT NULL,
    workspace_identity VARCHAR(200) NOT NULL,
    selected_worker_id VARCHAR(80) NOT NULL,
    project_policy_revision BIGINT NOT NULL,
    source_revision BIGINT NOT NULL DEFAULT 0,
    source_fingerprint_sha256 VARCHAR(64) NOT NULL,
    source_state VARCHAR(24) NOT NULL DEFAULT 'CLEAN',
    validation_state VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    review_state VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    integration_state VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    release_state VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_development_change_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT uk_development_change_change_key UNIQUE (change_key),
    CONSTRAINT uk_development_change_id_project UNIQUE (id, project_id),
    CONSTRAINT uk_development_change_project_workspace_branch
        UNIQUE (project_id, workspace_branch),
    CONSTRAINT uk_development_change_workspace_identity UNIQUE (workspace_identity),
    CONSTRAINT ck_development_change_status
        CHECK (status IN ('OPEN', 'PAUSED', 'ABANDONED', 'COMPLETED')),
    CONSTRAINT ck_development_change_base_ref
        CHECK (base_ref ~ '^refs/heads/[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$'),
    CONSTRAINT ck_development_change_base_commit
        CHECK (base_commit ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    CONSTRAINT ck_development_change_worker_id
        CHECK (selected_worker_id ~ '^[a-z0-9]([a-z0-9._-]{0,78}[a-z0-9])?$'),
    CONSTRAINT ck_development_change_workspace_branch
        CHECK (workspace_branch = 'atenea/change-' || change_key::text),
    CONSTRAINT ck_development_change_workspace_identity
        CHECK (workspace_identity = 'remote:' || selected_worker_id
            || ':change:' || change_key::text),
    CONSTRAINT ck_development_change_policy_revision
        CHECK (project_policy_revision >= 1),
    CONSTRAINT ck_development_change_source_revision
        CHECK (source_revision >= 0),
    CONSTRAINT ck_development_change_source_fingerprint
        CHECK (source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_development_change_source_state
        CHECK (source_state IN ('CLEAN', 'DIRTY', 'STALE', 'BLOCKED')),
    CONSTRAINT ck_development_change_validation_state
        CHECK (validation_state IN ('NOT_STARTED', 'CURRENT', 'STALE', 'BLOCKED')),
    CONSTRAINT ck_development_change_review_state
        CHECK (review_state IN ('NOT_STARTED', 'CURRENT', 'STALE', 'BLOCKED')),
    CONSTRAINT ck_development_change_integration_state
        CHECK (integration_state IN ('NOT_STARTED', 'CURRENT', 'STALE', 'BLOCKED')),
    CONSTRAINT ck_development_change_release_state
        CHECK (release_state IN ('NOT_STARTED', 'CURRENT', 'STALE', 'BLOCKED')),
    CONSTRAINT ck_development_change_timestamps
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_development_change_version
        CHECK (version >= 0)
);

CREATE INDEX idx_development_change_project_status
    ON development_change (project_id, status, updated_at DESC);

CREATE INDEX idx_development_change_source_state
    ON development_change (source_state, updated_at)
    WHERE source_state IN ('DIRTY', 'STALE', 'BLOCKED');

ALTER TABLE work_session
    ADD COLUMN development_change_id BIGINT,
    ADD CONSTRAINT fk_work_session_development_change_project
        FOREIGN KEY (development_change_id, project_id)
        REFERENCES development_change (id, project_id) ON DELETE RESTRICT;

CREATE INDEX idx_work_session_development_change
    ON work_session (development_change_id, last_activity_at DESC)
    WHERE development_change_id IS NOT NULL;

CREATE UNIQUE INDEX uk_work_session_active_change
    ON work_session (development_change_id)
    WHERE development_change_id IS NOT NULL
      AND status IN ('OPEN', 'CLOSING');
