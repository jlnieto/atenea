CREATE TABLE worker_node (
    id VARCHAR(80) PRIMARY KEY,
    protocol_version VARCHAR(80) NOT NULL,
    endpoint VARCHAR(500) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    healthy BOOLEAN NOT NULL DEFAULT FALSE,
    normal_capacity INTEGER NOT NULL,
    heavy_capacity INTEGER NOT NULL,
    normal_in_use INTEGER NOT NULL DEFAULT 0,
    heavy_in_use INTEGER NOT NULL DEFAULT 0,
    capabilities TEXT NOT NULL DEFAULT '',
    last_heartbeat_at TIMESTAMPTZ,
    unavailable_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_worker_node_capacity
        CHECK (
            normal_capacity BETWEEN 0 AND 64
            AND heavy_capacity BETWEEN 0 AND normal_capacity
            AND normal_in_use BETWEEN 0 AND normal_capacity
            AND heavy_in_use BETWEEN 0 AND heavy_capacity
        )
);

ALTER TABLE work_session
    ADD COLUMN execution_target VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN selected_worker_id VARCHAR(80),
    ADD COLUMN workspace_identity VARCHAR(200);

UPDATE work_session
SET workspace_identity = 'local:work-session:' || id
WHERE workspace_identity IS NULL;

ALTER TABLE work_session
    ALTER COLUMN workspace_identity SET NOT NULL,
    ADD CONSTRAINT ck_work_session_execution_target
        CHECK (execution_target IN ('LOCAL', 'REMOTE')),
    ADD CONSTRAINT ck_work_session_execution_affinity
        CHECK (
            (execution_target = 'LOCAL' AND selected_worker_id IS NULL)
            OR (execution_target = 'REMOTE' AND selected_worker_id IS NOT NULL)
        ),
    ADD CONSTRAINT fk_work_session_selected_worker
        FOREIGN KEY (selected_worker_id) REFERENCES worker_node (id) ON DELETE RESTRICT;

ALTER TABLE agent_run DROP CONSTRAINT ck_agent_run_status;
ALTER TABLE agent_run DROP CONSTRAINT ck_agent_run_finished_at_consistency;
DROP INDEX uk_agent_run_running_session;

ALTER TABLE agent_run
    ADD COLUMN execution_target VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN selected_worker_id VARCHAR(80),
    ADD COLUMN workspace_identity VARCHAR(200),
    ADD COLUMN dispatch_id UUID,
    ADD COLUMN remote_execution_id VARCHAR(100),
    ADD COLUMN workload_class VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN last_heartbeat_at TIMESTAMPTZ,
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN queued_at TIMESTAMPTZ,
    ADD COLUMN cancellation_requested_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_started_at TIMESTAMPTZ,
    ADD COLUMN status_reason VARCHAR(500);

UPDATE agent_run run
SET execution_target = session.execution_target,
    selected_worker_id = session.selected_worker_id,
    workspace_identity = session.workspace_identity
FROM work_session session
WHERE run.session_id = session.id;

ALTER TABLE agent_run
    ALTER COLUMN workspace_identity SET NOT NULL,
    ADD CONSTRAINT uk_agent_run_dispatch_id UNIQUE (dispatch_id),
    ADD CONSTRAINT fk_agent_run_selected_worker
        FOREIGN KEY (selected_worker_id) REFERENCES worker_node (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_agent_run_execution_target
        CHECK (execution_target IN ('LOCAL', 'REMOTE')),
    ADD CONSTRAINT ck_agent_run_workload_class
        CHECK (workload_class IN ('NORMAL', 'HEAVY')),
    ADD CONSTRAINT ck_agent_run_status
        CHECK (status IN (
            'QUEUED', 'STARTING', 'RUNNING', 'CANCELLING', 'RECONCILING',
            'SUCCEEDED', 'FAILED', 'CANCELLED'
        )),
    ADD CONSTRAINT ck_agent_run_finished_at_consistency
        CHECK (
            (status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLING', 'RECONCILING')
                AND finished_at IS NULL)
            OR (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                AND finished_at IS NOT NULL)
        ),
    ADD CONSTRAINT ck_agent_run_remote_identity
        CHECK (
            (execution_target = 'LOCAL'
                AND selected_worker_id IS NULL
                AND dispatch_id IS NULL)
            OR (execution_target = 'REMOTE'
                AND selected_worker_id IS NOT NULL
                AND dispatch_id IS NOT NULL)
        );

CREATE UNIQUE INDEX uk_agent_run_non_terminal_session
    ON agent_run (session_id)
    WHERE status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLING', 'RECONCILING');

CREATE INDEX idx_agent_run_remote_reconciliation
    ON agent_run (execution_target, status, created_at)
    WHERE execution_target = 'REMOTE';

CREATE INDEX idx_worker_node_eligibility
    ON worker_node (enabled, healthy, last_heartbeat_at);
