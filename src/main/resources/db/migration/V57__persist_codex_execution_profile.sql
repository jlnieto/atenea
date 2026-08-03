ALTER TABLE project
    ADD COLUMN default_codex_model_id VARCHAR(80),
    ADD COLUMN default_codex_reasoning_effort VARCHAR(16),
    ADD CONSTRAINT ck_project_codex_profile_default
        CHECK (
            (default_codex_model_id IS NULL
                OR default_codex_model_id ~ '^[a-z0-9][a-z0-9._-]{0,79}$')
            AND (default_codex_reasoning_effort IS NULL
                OR default_codex_reasoning_effort IN (
                    'none', 'low', 'medium', 'high', 'xhigh', 'max'
                ))
        );

ALTER TABLE work_session
    ADD COLUMN default_codex_model_id VARCHAR(80),
    ADD COLUMN default_codex_reasoning_effort VARCHAR(16),
    ADD CONSTRAINT ck_work_session_codex_profile_default
        CHECK (
            (default_codex_model_id IS NULL
                OR default_codex_model_id ~ '^[a-z0-9][a-z0-9._-]{0,79}$')
            AND (default_codex_reasoning_effort IS NULL
                OR default_codex_reasoning_effort IN (
                    'none', 'low', 'medium', 'high', 'xhigh', 'max'
                ))
        );

ALTER TABLE agent_run
    ADD COLUMN codex_model_id VARCHAR(80),
    ADD COLUMN codex_model_source VARCHAR(24),
    ADD COLUMN codex_reasoning_effort VARCHAR(16),
    ADD COLUMN codex_effort_source VARCHAR(24),
    ADD COLUMN codex_catalog_revision VARCHAR(64),
    ADD COLUMN codex_version VARCHAR(32),
    ADD CONSTRAINT ck_agent_run_codex_profile_complete
        CHECK (
            (codex_model_id IS NULL
                AND codex_model_source IS NULL
                AND codex_reasoning_effort IS NULL
                AND codex_effort_source IS NULL
                AND codex_catalog_revision IS NULL
                AND codex_version IS NULL)
            OR (codex_model_id ~ '^[a-z0-9][a-z0-9._-]{0,79}$'
                AND codex_model_source IN (
                    'NEXT_TURN', 'WORK_SESSION', 'PROJECT', 'PLATFORM',
                    'WORKER_DEFAULT'
                )
                AND codex_reasoning_effort IN (
                    'none', 'low', 'medium', 'high', 'xhigh', 'max'
                )
                AND codex_effort_source IN (
                    'NEXT_TURN', 'WORK_SESSION', 'PROJECT', 'PLATFORM',
                    'WORKER_DEFAULT'
                )
                AND codex_catalog_revision ~ '^[0-9a-f]{64}$'
                AND codex_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$')
        );

CREATE TABLE worker_codex_catalog (
    worker_id VARCHAR(80) NOT NULL,
    catalog_revision VARCHAR(64) NOT NULL,
    schema_version VARCHAR(80) NOT NULL,
    codex_version VARCHAR(32) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (worker_id, catalog_revision),
    CONSTRAINT fk_worker_codex_catalog_worker
        FOREIGN KEY (worker_id) REFERENCES worker_node (id) ON DELETE RESTRICT,
    CONSTRAINT ck_worker_codex_catalog_revision
        CHECK (catalog_revision ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_worker_codex_catalog_schema
        CHECK (schema_version = 'codex-model-catalog-v1'),
    CONSTRAINT ck_worker_codex_catalog_version
        CHECK (codex_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$')
);

CREATE TABLE worker_codex_model (
    worker_id VARCHAR(80) NOT NULL,
    catalog_revision VARCHAR(64) NOT NULL,
    model_id VARCHAR(80) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    default_effort VARCHAR(16) NOT NULL,
    availability VARCHAR(16) NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (worker_id, catalog_revision, model_id),
    CONSTRAINT fk_worker_codex_model_catalog
        FOREIGN KEY (worker_id, catalog_revision)
        REFERENCES worker_codex_catalog (worker_id, catalog_revision)
        ON DELETE RESTRICT,
    CONSTRAINT ck_worker_codex_model_id
        CHECK (model_id ~ '^[a-z0-9][a-z0-9._-]{0,79}$'),
    CONSTRAINT ck_worker_codex_model_default_effort
        CHECK (default_effort IN ('none', 'low', 'medium', 'high', 'xhigh', 'max')),
    CONSTRAINT ck_worker_codex_model_availability
        CHECK (availability IN ('AVAILABLE', 'DEPRECATED', 'BLOCKED')),
    CONSTRAINT ck_worker_codex_model_position
        CHECK (position BETWEEN 0 AND 31),
    UNIQUE (worker_id, catalog_revision, position)
);

CREATE TABLE worker_codex_model_effort (
    worker_id VARCHAR(80) NOT NULL,
    catalog_revision VARCHAR(64) NOT NULL,
    model_id VARCHAR(80) NOT NULL,
    effort VARCHAR(16) NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (worker_id, catalog_revision, model_id, effort),
    CONSTRAINT fk_worker_codex_model_effort_model
        FOREIGN KEY (worker_id, catalog_revision, model_id)
        REFERENCES worker_codex_model (worker_id, catalog_revision, model_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_worker_codex_model_effort
        CHECK (effort IN ('none', 'low', 'medium', 'high', 'xhigh', 'max')),
    CONSTRAINT ck_worker_codex_model_effort_position
        CHECK (position BETWEEN 0 AND 5),
    UNIQUE (worker_id, catalog_revision, model_id, position)
);

ALTER TABLE worker_codex_model
    ADD CONSTRAINT fk_worker_codex_model_default_effort
    FOREIGN KEY (worker_id, catalog_revision, model_id, default_effort)
    REFERENCES worker_codex_model_effort (
        worker_id, catalog_revision, model_id, effort
    )
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_worker_codex_catalog_current
    ON worker_codex_catalog (worker_id, generated_at DESC, catalog_revision);
