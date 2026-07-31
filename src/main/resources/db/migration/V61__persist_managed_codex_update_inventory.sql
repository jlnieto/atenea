CREATE TABLE worker_codex_release_inventory (
    inventory_id UUID PRIMARY KEY,
    worker_id VARCHAR(80) NOT NULL,
    codex_version VARCHAR(32) NOT NULL,
    release_digest_sha256 VARCHAR(64) NOT NULL,
    installation_state VARCHAR(16) NOT NULL,
    link_state VARCHAR(16) NOT NULL,
    compatibility_state VARCHAR(24) NOT NULL,
    catalog_revision VARCHAR(64),
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_worker_codex_release_worker
        FOREIGN KEY (worker_id) REFERENCES worker_node (id) ON DELETE RESTRICT,
    CONSTRAINT uk_worker_codex_release_identity
        UNIQUE (worker_id, codex_version, release_digest_sha256),
    CONSTRAINT uk_worker_codex_release_owned_identity
        UNIQUE (worker_id, inventory_id),
    CONSTRAINT ck_worker_codex_release_version
        CHECK (codex_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    CONSTRAINT ck_worker_codex_release_digest
        CHECK (release_digest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_worker_codex_release_installation
        CHECK (installation_state IN ('DISCOVERED', 'STAGED', 'INSTALLED')),
    CONSTRAINT ck_worker_codex_release_link
        CHECK (link_state IN ('NONE', 'CURRENT', 'PREVIOUS')),
    CONSTRAINT ck_worker_codex_release_link_installed
        CHECK (link_state = 'NONE' OR installation_state = 'INSTALLED'),
    CONSTRAINT ck_worker_codex_release_compatibility
        CHECK (compatibility_state IN ('UNKNOWN', 'COMPATIBLE', 'INCOMPATIBLE')),
    CONSTRAINT ck_worker_codex_release_catalog
        CHECK (catalog_revision IS NULL OR catalog_revision ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uk_worker_codex_release_current
    ON worker_codex_release_inventory (worker_id) WHERE link_state = 'CURRENT';
CREATE UNIQUE INDEX uk_worker_codex_release_previous
    ON worker_codex_release_inventory (worker_id) WHERE link_state = 'PREVIOUS';
CREATE INDEX idx_worker_codex_release_candidate
    ON worker_codex_release_inventory (
        worker_id, compatibility_state, installation_state, observed_at DESC
    ) WHERE link_state = 'NONE';

CREATE TABLE worker_codex_activation_barrier (
    worker_id VARCHAR(80) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_worker_codex_activation_barrier_worker
        FOREIGN KEY (worker_id) REFERENCES worker_node (id) ON DELETE RESTRICT
);

CREATE TABLE worker_codex_update_plan (
    plan_id UUID PRIMARY KEY,
    worker_id VARCHAR(80) NOT NULL,
    requested_by BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    current_inventory_id UUID,
    previous_inventory_id UUID,
    candidate_inventory_id UUID,
    state VARCHAR(16) NOT NULL,
    compatibility_state VARCHAR(24) NOT NULL,
    worker_health_gate VARCHAR(16) NOT NULL,
    current_link_gate VARCHAR(16) NOT NULL,
    catalog_alignment_gate VARCHAR(16) NOT NULL,
    candidate_compatibility_gate VARCHAR(16) NOT NULL,
    expected_service_impact VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_worker_codex_update_plan_worker
        FOREIGN KEY (worker_id) REFERENCES worker_node (id) ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_update_plan_operator
        FOREIGN KEY (requested_by) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_update_plan_current
        FOREIGN KEY (worker_id, current_inventory_id)
        REFERENCES worker_codex_release_inventory (worker_id, inventory_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_update_plan_previous
        FOREIGN KEY (worker_id, previous_inventory_id)
        REFERENCES worker_codex_release_inventory (worker_id, inventory_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_update_plan_candidate
        FOREIGN KEY (worker_id, candidate_inventory_id)
        REFERENCES worker_codex_release_inventory (worker_id, inventory_id)
        ON DELETE RESTRICT,
    CONSTRAINT uk_worker_codex_update_plan_idempotency
        UNIQUE (requested_by, idempotency_key),
    CONSTRAINT ck_worker_codex_update_plan_state
        CHECK (state IN ('READY', 'BLOCKED', 'ACTIVATED')),
    CONSTRAINT ck_worker_codex_update_plan_compatibility
        CHECK (compatibility_state IN ('COMPATIBLE', 'BLOCKED')),
    CONSTRAINT ck_worker_codex_update_plan_worker_gate
        CHECK (worker_health_gate IN ('PASS', 'BLOCKED')),
    CONSTRAINT ck_worker_codex_update_plan_current_gate
        CHECK (current_link_gate IN ('PASS', 'BLOCKED')),
    CONSTRAINT ck_worker_codex_update_plan_catalog_gate
        CHECK (catalog_alignment_gate IN ('PASS', 'BLOCKED')),
    CONSTRAINT ck_worker_codex_update_plan_candidate_gate
        CHECK (candidate_compatibility_gate IN ('PASS', 'BLOCKED')),
    CONSTRAINT ck_worker_codex_update_plan_impact
        CHECK (expected_service_impact =
            'No installation or restart; a later activation would restart only the exact Codex/worker boundary, never project runtimes or unrelated slots.'),
    CONSTRAINT ck_worker_codex_update_plan_projection
        CHECK (
            (state IN ('READY', 'ACTIVATED') AND compatibility_state = 'COMPATIBLE'
                AND worker_health_gate = 'PASS'
                AND current_link_gate = 'PASS'
                AND catalog_alignment_gate = 'PASS'
                AND candidate_compatibility_gate = 'PASS'
                AND current_inventory_id IS NOT NULL
                AND candidate_inventory_id IS NOT NULL)
            OR (state = 'BLOCKED' AND compatibility_state = 'BLOCKED'
                AND (worker_health_gate = 'BLOCKED'
                    OR current_link_gate = 'BLOCKED'
                    OR catalog_alignment_gate = 'BLOCKED'
                    OR candidate_compatibility_gate = 'BLOCKED'))
        ),
    CONSTRAINT ck_worker_codex_update_plan_distinct
        CHECK (current_inventory_id IS NULL OR candidate_inventory_id IS NULL
            OR current_inventory_id <> candidate_inventory_id)
);

CREATE INDEX idx_worker_codex_update_plan_worker_created
    ON worker_codex_update_plan (worker_id, created_at DESC);

ALTER TABLE worker_codex_update_plan
    ADD CONSTRAINT uk_worker_codex_update_plan_candidate
    UNIQUE (plan_id, worker_id, candidate_inventory_id);

CREATE TABLE worker_codex_stage_operation (
    stage_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    worker_id VARCHAR(80) NOT NULL,
    requested_by BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    candidate_inventory_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    release_digest_sha256 VARCHAR(64) NOT NULL,
    catalog_revision VARCHAR(64) NOT NULL,
    release_manifest_sha256 VARCHAR(64) NOT NULL,
    schema_manifest_sha256 VARCHAR(64) NOT NULL,
    release_verification_gate VARCHAR(16) NOT NULL,
    schema_generation_gate VARCHAR(16) NOT NULL,
    retention_gate VARCHAR(16) NOT NULL,
    current_link_fingerprint VARCHAR(64) NOT NULL,
    previous_link_fingerprint VARCHAR(64) NOT NULL,
    links_changed BOOLEAN NOT NULL,
    values_exposed BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_worker_codex_stage_plan_candidate
        FOREIGN KEY (plan_id, worker_id, candidate_inventory_id)
        REFERENCES worker_codex_update_plan
            (plan_id, worker_id, candidate_inventory_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_stage_operator
        FOREIGN KEY (requested_by) REFERENCES operator_account (id)
        ON DELETE RESTRICT,
    CONSTRAINT uk_worker_codex_stage_idempotency
        UNIQUE (requested_by, idempotency_key),
    CONSTRAINT uk_worker_codex_stage_candidate
        UNIQUE (plan_id, candidate_inventory_id),
    CONSTRAINT ck_worker_codex_stage_state
        CHECK (state = 'STAGED'),
    CONSTRAINT ck_worker_codex_stage_release_digest
        CHECK (release_digest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_worker_codex_stage_catalog_revision
        CHECK (catalog_revision ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_worker_codex_stage_release_manifest
        CHECK (release_manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_worker_codex_stage_schema_manifest
        CHECK (schema_manifest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_worker_codex_stage_link_fingerprints
        CHECK (current_link_fingerprint ~ '^[0-9a-f]{64}$'
            AND previous_link_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_worker_codex_stage_gates
        CHECK (release_verification_gate = 'PASS'
            AND schema_generation_gate = 'PASS'
            AND retention_gate = 'PASS'),
    CONSTRAINT ck_worker_codex_stage_no_link_change
        CHECK (links_changed = FALSE),
    CONSTRAINT ck_worker_codex_stage_no_values
        CHECK (values_exposed = FALSE),
    CONSTRAINT ck_worker_codex_stage_time
        CHECK (completed_at >= created_at)
);

CREATE INDEX idx_worker_codex_stage_worker_created
    ON worker_codex_stage_operation (worker_id, created_at DESC);

CREATE TABLE worker_codex_activation_authorization (
    authorization_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL,
    worker_id VARCHAR(80) NOT NULL,
    requested_by BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    current_inventory_id UUID NOT NULL,
    candidate_inventory_id UUID NOT NULL,
    current_version VARCHAR(64) NOT NULL,
    candidate_version VARCHAR(64) NOT NULL,
    release_digest_sha256 VARCHAR(64) NOT NULL,
    authorization_digest_sha256 VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    consumed_activation_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_worker_codex_activation_auth_plan_candidate
        FOREIGN KEY (plan_id, worker_id, candidate_inventory_id)
        REFERENCES worker_codex_update_plan
            (plan_id, worker_id, candidate_inventory_id) ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_activation_auth_current
        FOREIGN KEY (worker_id, current_inventory_id)
        REFERENCES worker_codex_release_inventory (worker_id, inventory_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_activation_auth_operator
        FOREIGN KEY (requested_by) REFERENCES operator_account (id)
        ON DELETE RESTRICT,
    CONSTRAINT uk_worker_codex_activation_auth_idempotency
        UNIQUE (requested_by, idempotency_key),
    CONSTRAINT uk_worker_codex_activation_auth_exact
        UNIQUE (authorization_id, plan_id, worker_id, candidate_inventory_id),
    CONSTRAINT ck_worker_codex_activation_auth_versions
        CHECK (current_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'
            AND candidate_version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'
            AND current_version <> candidate_version),
    CONSTRAINT ck_worker_codex_activation_auth_digests
        CHECK (release_digest_sha256 ~ '^[0-9a-f]{64}$'
            AND authorization_digest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_worker_codex_activation_auth_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_worker_codex_activation_auth_consumption
        CHECK ((consumed_at IS NULL AND consumed_activation_id IS NULL)
            OR (consumed_at IS NOT NULL AND consumed_activation_id IS NOT NULL
                AND consumed_at >= created_at))
);

CREATE INDEX idx_worker_codex_activation_auth_worker_expiry
    ON worker_codex_activation_authorization (worker_id, expires_at DESC);

CREATE TABLE worker_codex_activation_operation (
    activation_id UUID PRIMARY KEY,
    authorization_id UUID NOT NULL UNIQUE,
    plan_id UUID NOT NULL,
    worker_id VARCHAR(80) NOT NULL,
    requested_by BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    candidate_inventory_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    schema_comparison_gate VARCHAR(16) NOT NULL,
    focused_contracts_gate VARCHAR(16) NOT NULL,
    worker_health_gate VARCHAR(16) NOT NULL,
    canary_gate VARCHAR(16) NOT NULL,
    current_before_fingerprint VARCHAR(64) NOT NULL,
    previous_before_fingerprint VARCHAR(64) NOT NULL,
    current_after_fingerprint VARCHAR(64) NOT NULL,
    previous_after_fingerprint VARCHAR(64) NOT NULL,
    automatic_restore VARCHAR(16) NOT NULL,
    values_exposed BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_worker_codex_activation_auth
        FOREIGN KEY (authorization_id, plan_id, worker_id, candidate_inventory_id)
        REFERENCES worker_codex_activation_authorization
            (authorization_id, plan_id, worker_id, candidate_inventory_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_activation_operator
        FOREIGN KEY (requested_by) REFERENCES operator_account (id)
        ON DELETE RESTRICT,
    CONSTRAINT uk_worker_codex_activation_idempotency
        UNIQUE (requested_by, idempotency_key),
    CONSTRAINT uk_worker_codex_activation_plan_candidate
        UNIQUE (plan_id, candidate_inventory_id),
    CONSTRAINT ck_worker_codex_activation_state CHECK (state = 'ACTIVATED'),
    CONSTRAINT ck_worker_codex_activation_gates
        CHECK (schema_comparison_gate = 'PASS'
            AND focused_contracts_gate = 'PASS'
            AND worker_health_gate = 'PASS'
            AND canary_gate = 'PASS'),
    CONSTRAINT ck_worker_codex_activation_fingerprints
        CHECK (current_before_fingerprint ~ '^[0-9a-f]{64}$'
            AND previous_before_fingerprint ~ '^[0-9a-f]{64}$'
            AND current_after_fingerprint ~ '^[0-9a-f]{64}$'
            AND previous_after_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_worker_codex_activation_restore
        CHECK (automatic_restore IN ('NOT_REQUIRED', 'PASS')),
    CONSTRAINT ck_worker_codex_activation_no_values CHECK (values_exposed = FALSE),
    CONSTRAINT ck_worker_codex_activation_time CHECK (completed_at >= created_at)
);

CREATE INDEX idx_worker_codex_activation_worker_created
    ON worker_codex_activation_operation (worker_id, created_at DESC);
