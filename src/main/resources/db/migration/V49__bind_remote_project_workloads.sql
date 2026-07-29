ALTER TABLE work_session
    ADD COLUMN remote_session_id UUID,
    ADD COLUMN remote_workload_kind VARCHAR(80);

UPDATE work_session
SET remote_session_id = md5('atenea-remote-work-session:' || id)::uuid,
    remote_workload_kind = 'synthetic-routing-v1',
    workspace_identity = 'remote:' || selected_worker_id || ':work-session:'
        || md5('atenea-remote-work-session:' || id)::uuid
WHERE execution_target = 'REMOTE';

ALTER TABLE work_session
    ADD CONSTRAINT uk_work_session_remote_session_id UNIQUE (remote_session_id),
    ADD CONSTRAINT ck_work_session_remote_workload
        CHECK (
            (execution_target = 'LOCAL'
                AND remote_session_id IS NULL
                AND remote_workload_kind IS NULL)
            OR (execution_target = 'REMOTE'
                AND remote_session_id IS NOT NULL
                AND remote_workload_kind IN ('synthetic-routing-v1', 'project-codex-v1'))
        );

ALTER TABLE agent_run
    ADD COLUMN remote_session_id UUID,
    ADD COLUMN workload_kind VARCHAR(80),
    ADD COLUMN project_identity VARCHAR(80),
    ADD COLUMN repository_url VARCHAR(500),
    ADD COLUMN repository_branch VARCHAR(180),
    ADD COLUMN repository_commit VARCHAR(64),
    ADD COLUMN manifest_sha256 VARCHAR(64);

UPDATE agent_run run
SET remote_session_id = session.remote_session_id,
    workload_kind = session.remote_workload_kind,
    workspace_identity = session.workspace_identity
FROM work_session session
WHERE run.session_id = session.id
  AND run.execution_target = 'REMOTE';

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_remote_workload
        CHECK (
            (execution_target = 'LOCAL'
                AND remote_session_id IS NULL
                AND workload_kind IS NULL
                AND project_identity IS NULL
                AND repository_url IS NULL
                AND repository_branch IS NULL
                AND repository_commit IS NULL
                AND manifest_sha256 IS NULL)
            OR (execution_target = 'REMOTE'
                AND remote_session_id IS NOT NULL
                AND (
                    (workload_kind = 'synthetic-routing-v1'
                        AND project_identity IS NULL
                        AND repository_url IS NULL
                        AND repository_branch IS NULL
                        AND repository_commit IS NULL
                        AND manifest_sha256 IS NULL)
                    OR (workload_kind = 'project-codex-v1'
                        AND project_identity IS NOT NULL
                        AND repository_url IS NOT NULL
                        AND repository_branch IS NOT NULL
                        AND repository_commit ~ '^[0-9a-f]{40}$'
                        AND manifest_sha256 ~ '^[0-9a-f]{64}$')
                ))
        );

CREATE INDEX idx_agent_run_project_workload
    ON agent_run (workload_kind, project_identity, repository_commit)
    WHERE workload_kind = 'project-codex-v1';
