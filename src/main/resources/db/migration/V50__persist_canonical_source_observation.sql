ALTER TABLE work_session
    ADD COLUMN canonical_source_ref VARCHAR(220),
    ADD COLUMN canonical_source_commit VARCHAR(64),
    ADD COLUMN canonical_source_observation_sha256 VARCHAR(64),
    ADD COLUMN canonical_source_observed_at TIMESTAMPTZ;

ALTER TABLE work_session
    ADD CONSTRAINT ck_work_session_canonical_source
        CHECK (
            (canonical_source_ref IS NULL
                AND canonical_source_commit IS NULL
                AND canonical_source_observation_sha256 IS NULL
                AND canonical_source_observed_at IS NULL)
            OR (canonical_source_ref IS NOT NULL
                AND canonical_source_commit ~ '^[0-9a-f]{40}$'
                AND canonical_source_observation_sha256 ~ '^[0-9a-f]{64}$'
                AND canonical_source_observed_at IS NOT NULL)
        );

ALTER TABLE agent_run
    ADD COLUMN worker_mirror_commit VARCHAR(64),
    ADD CONSTRAINT ck_agent_run_worker_mirror_commit
        CHECK (
            worker_mirror_commit IS NULL
            OR (
                execution_target = 'REMOTE'
                AND workload_kind = 'project-codex-v1'
                AND worker_mirror_commit ~ '^[0-9a-f]{40}$'
                AND worker_mirror_commit = repository_commit
            )
        );
