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
        CHECK (state IN ('READY', 'BLOCKED')),
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
            (state = 'READY' AND compatibility_state = 'COMPATIBLE'
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
