ALTER TABLE work_session
    ADD COLUMN attachment_policy_revision VARCHAR(80),
    ADD CONSTRAINT ck_work_session_attachment_policy
        CHECK (
            attachment_policy_revision IS NULL
            OR (
                attachment_policy_revision = 'atenea-real-attachments-v1'
                AND execution_target = 'REMOTE'
                AND selected_worker_id = 'ax42-01'
                AND remote_session_id IS NOT NULL
                AND remote_workload_kind = 'project-codex-v1'
                AND workspace_identity = 'remote:' || selected_worker_id
                    || ':work-session:' || remote_session_id::text
            )
        ),
    ADD CONSTRAINT uk_work_session_project_ownership
        UNIQUE (id, project_id),
    ADD CONSTRAINT uk_work_session_real_storage_ownership
        UNIQUE (id, project_id, selected_worker_id, remote_session_id, workspace_identity);

ALTER TABLE session_turn
    ADD COLUMN client_request_id UUID,
    ADD COLUMN request_fingerprint_sha256 VARCHAR(64),
    ADD CONSTRAINT ck_session_turn_request_identity
        CHECK (
            (client_request_id IS NULL AND request_fingerprint_sha256 IS NULL)
            OR (
                client_request_id IS NOT NULL
                AND request_fingerprint_sha256 IS NOT NULL
                AND request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
                AND actor = 'OPERATOR'
                AND internal = FALSE
            )
        ),
    ADD CONSTRAINT uk_session_turn_request_identity
        UNIQUE (session_id, client_request_id),
    ADD CONSTRAINT uk_session_turn_session_ownership
        UNIQUE (session_id, id);

ALTER TABLE work_session_attachment
    ADD COLUMN storage_scope VARCHAR(24),
    ADD COLUMN remote_session_id UUID,
    ADD COLUMN workspace_identity VARCHAR(200),
    ADD CONSTRAINT ck_work_session_attachment_storage_scope
        CHECK (
            (storage_scope IS NULL
                AND remote_session_id IS NULL
                AND workspace_identity IS NULL)
            OR (
                storage_scope IS NOT NULL
                AND storage_scope = 'REAL_SESSION'
                AND remote_session_id IS NOT NULL
                AND workspace_identity IS NOT NULL
                AND workspace_identity = 'remote:' || worker_id
                    || ':work-session:' || remote_session_id::text
                AND source = 'OPERATOR_UPLOAD'
                AND retention_class = 'SESSION'
            )
        ),
    ADD CONSTRAINT uk_work_session_attachment_session_ownership
        UNIQUE (work_session_id, id),
    ADD CONSTRAINT fk_work_session_attachment_session_project
        FOREIGN KEY (work_session_id, project_id)
        REFERENCES work_session (id, project_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_session_attachment_real_storage_owner
        FOREIGN KEY (
            work_session_id,
            project_id,
            worker_id,
            remote_session_id,
            workspace_identity
        )
        REFERENCES work_session (
            id,
            project_id,
            selected_worker_id,
            remote_session_id,
            workspace_identity
        )
        ON DELETE RESTRICT;

CREATE TABLE session_turn_attachment (
    work_session_id BIGINT NOT NULL,
    session_turn_id BIGINT NOT NULL,
    attachment_id UUID NOT NULL,
    position SMALLINT NOT NULL,
    PRIMARY KEY (session_turn_id, attachment_id),
    CONSTRAINT uk_session_turn_attachment_position
        UNIQUE (session_turn_id, position),
    CONSTRAINT fk_session_turn_attachment_turn_owner
        FOREIGN KEY (work_session_id, session_turn_id)
        REFERENCES session_turn (session_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_session_turn_attachment_attachment_owner
        FOREIGN KEY (work_session_id, attachment_id)
        REFERENCES work_session_attachment (work_session_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_session_turn_attachment_position
        CHECK (position BETWEEN 0 AND 3)
);

CREATE INDEX idx_session_turn_attachment_session_order
    ON session_turn_attachment (work_session_id, session_turn_id, position);

ALTER TABLE agent_run
    ADD COLUMN attachment_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN attachment_bytes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN attachment_manifest_sha256 VARCHAR(64),
    DROP CONSTRAINT ck_agent_run_remote_workload,
    DROP CONSTRAINT ck_agent_run_worker_mirror_commit,
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
                    OR (workload_kind IN ('project-codex-v1', 'project-codex-v3')
                        AND project_identity IS NOT NULL
                        AND repository_url IS NOT NULL
                        AND repository_branch IS NOT NULL
                        AND repository_commit IS NOT NULL
                        AND repository_commit ~ '^[0-9a-f]{40}$'
                        AND manifest_sha256 IS NOT NULL
                        AND manifest_sha256 ~ '^[0-9a-f]{64}$')
                ))
        ),
    ADD CONSTRAINT ck_agent_run_worker_mirror_commit
        CHECK (
            worker_mirror_commit IS NULL
            OR (
                execution_target = 'REMOTE'
                AND workload_kind IN ('project-codex-v1', 'project-codex-v3')
                AND worker_mirror_commit ~ '^[0-9a-f]{40}$'
                AND worker_mirror_commit = repository_commit
            )
        ),
    ADD CONSTRAINT ck_agent_run_attachment_manifest
        CHECK (
            (attachment_count = 0
                AND attachment_bytes = 0
                AND attachment_manifest_sha256 IS NULL
                AND workload_kind IS DISTINCT FROM 'project-codex-v3')
            OR (
                attachment_count BETWEEN 1 AND 4
                AND attachment_bytes BETWEEN 1 AND 33554432
                AND attachment_manifest_sha256 IS NOT NULL
                AND attachment_manifest_sha256 ~ '^[0-9a-f]{64}$'
                AND execution_target = 'REMOTE'
                AND workload_kind = 'project-codex-v3'
            )
        );

CREATE INDEX idx_agent_run_attachment_manifest
    ON agent_run (attachment_manifest_sha256)
    WHERE attachment_manifest_sha256 IS NOT NULL;
