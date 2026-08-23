ALTER TABLE development_change
    ADD COLUMN workspace_ownership_fingerprint_sha256 VARCHAR(64);

ALTER TABLE development_change
    ADD CONSTRAINT ck_development_change_workspace_ownership_fingerprint
        CHECK (workspace_ownership_fingerprint_sha256 IS NULL
            OR workspace_ownership_fingerprint_sha256 ~ '^[0-9a-f]{64}$');

ALTER TABLE agent_run
    ADD COLUMN development_change_key UUID,
    ADD COLUMN change_base_commit VARCHAR(40),
    ADD COLUMN change_expected_canonical_commit VARCHAR(40),
    ADD COLUMN change_source_revision BIGINT,
    ADD COLUMN change_source_fingerprint_sha256 VARCHAR(64),
    ADD COLUMN change_workspace_ownership_fingerprint_sha256 VARCHAR(64);

ALTER TABLE agent_run
    DROP CONSTRAINT ck_agent_run_remote_workload,
    DROP CONSTRAINT ck_agent_run_attachment_manifest,
    DROP CONSTRAINT ck_agent_run_worker_mirror_commit,
    ADD CONSTRAINT ck_agent_run_remote_workload CHECK (
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
                OR (workload_kind IN (
                            'project-codex-v1', 'project-codex-v3', 'project-codex-v4')
                    AND project_identity IS NOT NULL
                    AND repository_url IS NOT NULL
                    AND repository_branch IS NOT NULL
                    AND repository_commit IS NOT NULL
                    AND repository_commit ~ '^[0-9a-f]{40}$'
                    AND manifest_sha256 IS NOT NULL
                    AND manifest_sha256 ~ '^[0-9a-f]{64}$')
            ))
    ),
    ADD CONSTRAINT ck_agent_run_attachment_manifest CHECK (
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
            AND workload_kind IN ('project-codex-v3', 'project-codex-v4')
        )
    ),
    ADD CONSTRAINT ck_agent_run_worker_mirror_commit CHECK (
        worker_mirror_commit IS NULL
        OR (
            execution_target = 'REMOTE'
            AND workload_kind IN (
                'project-codex-v1', 'project-codex-v3', 'project-codex-v4')
            AND worker_mirror_commit ~ '^[0-9a-f]{40}$'
            AND worker_mirror_commit = repository_commit
        )
    );

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_change_binding_complete CHECK (
        (development_change_key IS NULL
            AND change_base_commit IS NULL
            AND change_expected_canonical_commit IS NULL
            AND change_source_revision IS NULL
            AND change_source_fingerprint_sha256 IS NULL
            AND change_workspace_ownership_fingerprint_sha256 IS NULL)
        OR
        (development_change_key IS NOT NULL
            AND change_base_commit IS NOT NULL
            AND change_base_commit ~ '^[0-9a-f]{40}$'
            AND change_expected_canonical_commit IS NOT NULL
            AND change_expected_canonical_commit ~ '^[0-9a-f]{40}$'
            AND change_source_revision IS NOT NULL
            AND change_source_revision >= 0
            AND change_source_fingerprint_sha256 IS NOT NULL
            AND change_source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
            AND change_workspace_ownership_fingerprint_sha256 IS NOT NULL
            AND change_workspace_ownership_fingerprint_sha256 ~ '^[0-9a-f]{64}$')
    );

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_change_protocol CHECK (
        development_change_key IS NULL
        OR (workload_kind IS NOT NULL AND workload_kind = 'project-codex-v4')
    );
