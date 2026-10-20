-- Explicit validation intent. Historical attempts are evidence, not permission
-- to start more tests: no backfill/adoption runs during this migration.
CREATE TABLE change_validation_workflow (
    id UUID PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES work_session(id) ON DELETE RESTRICT,
    change_key UUID NOT NULL REFERENCES development_change(change_key) ON DELETE RESTRICT,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    source_revision BIGINT NOT NULL CHECK (source_revision >= 0),
    source_fingerprint_sha256 VARCHAR(64) NOT NULL CHECK (source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    remote_session_id UUID NOT NULL,
    worker_id VARCHAR(100) NOT NULL,
    workspace_identity VARCHAR(255) NOT NULL,
    workspace_branch VARCHAR(255) NOT NULL,
    canonical_commit VARCHAR(40) NOT NULL CHECK (canonical_commit ~ '^[0-9a-f]{40}$'),
    profile_revision VARCHAR(80) NOT NULL DEFAULT 'atenea-required-validation-v2',
    state VARCHAR(16) NOT NULL CHECK (state IN ('RUNNING','SUCCEEDED','FAILED','BLOCKED','STALE')),
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('QUEUED','DISPATCHING','MONITORING','FINISHED')),
    active_operation_id UUID REFERENCES validation_operation(id) ON DELETE RESTRICT,
    retry_of_operation_id UUID REFERENCES validation_operation(id) ON DELETE RESTRICT,
    retry_consumed BOOLEAN NOT NULL DEFAULT false,
    error_code VARCHAR(100) CHECK (error_code ~ '^[A-Z][A-Z0-9_]+$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    CONSTRAINT change_validation_terminal CHECK (
        (state='RUNNING' AND phase<>'FINISHED' AND finished_at IS NULL)
        OR (state<>'RUNNING' AND phase='FINISHED' AND finished_at IS NOT NULL)),
    CONSTRAINT change_validation_dispatch CHECK (
        (phase='QUEUED' AND active_operation_id IS NULL)
        OR (phase IN ('DISPATCHING','MONITORING') AND active_operation_id IS NOT NULL)
        OR phase='FINISHED')
);
CREATE UNIQUE INDEX change_validation_one_active_session
    ON change_validation_workflow(session_id) WHERE state='RUNNING';
CREATE INDEX change_validation_pending ON change_validation_workflow(updated_at,id) WHERE state='RUNNING';
CREATE INDEX change_validation_revision ON change_validation_workflow(session_id,source_revision,created_at DESC);
