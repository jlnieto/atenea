CREATE TABLE work_session_repository_role (
    id UUID PRIMARY KEY,
    work_session_id BIGINT NOT NULL REFERENCES work_session(id),
    change_identity UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    authority VARCHAR(16) NOT NULL,
    repository_url VARCHAR(300) NOT NULL,
    branch VARCHAR(160) NOT NULL,
    commit VARCHAR(40) NOT NULL,
    mirror_identity_sha256 VARCHAR(64) NOT NULL,
    worktree_identity_sha256 VARCHAR(64) NOT NULL,
    validation_profile VARCHAR(80) NOT NULL,
    readiness VARCHAR(24) NOT NULL,
    source_fingerprint_sha256 VARCHAR(64),
    validation_projection_sha256 VARCHAR(64),
    validated_at TIMESTAMPTZ,
    integration_ready_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (work_session_id, role),
    UNIQUE (change_identity, role),
    CHECK (role IN ('ATENEA_CODE', 'PROGRAMME_OPENSPEC', 'WORKER_SOURCE')),
    CHECK (authority = 'READ_WRITE'),
    CHECK (readiness IN ('DRAFT', 'VALIDATED', 'INTEGRATION_READY')),
    CHECK (commit ~ '^[0-9a-f]{40}$'),
    CHECK (mirror_identity_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (worktree_identity_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (source_fingerprint_sha256 IS NULL
        OR source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (validation_projection_sha256 IS NULL
        OR validation_projection_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (
        (readiness = 'DRAFT'
            AND source_fingerprint_sha256 IS NULL
            AND validation_projection_sha256 IS NULL
            AND validated_at IS NULL AND integration_ready_at IS NULL)
        OR (readiness = 'VALIDATED'
            AND source_fingerprint_sha256 IS NOT NULL
            AND validation_projection_sha256 IS NOT NULL
            AND validated_at IS NOT NULL
            AND integration_ready_at IS NULL)
        OR (readiness = 'INTEGRATION_READY'
            AND source_fingerprint_sha256 IS NOT NULL
            AND validation_projection_sha256 IS NOT NULL
            AND validated_at IS NOT NULL
            AND integration_ready_at IS NOT NULL)
    )
);
