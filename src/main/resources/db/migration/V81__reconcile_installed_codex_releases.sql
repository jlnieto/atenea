ALTER TABLE worker_codex_update_plan
    ADD COLUMN plan_kind VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN previous_bootstrap_state VARCHAR(24) NOT NULL DEFAULT 'PRESENT',
    ADD CONSTRAINT ck_worker_codex_update_plan_kind
        CHECK (plan_kind IN ('STANDARD', 'RECOVERY')),
    ADD CONSTRAINT ck_worker_codex_update_plan_previous_bootstrap
        CHECK (previous_bootstrap_state IN ('PRESENT', 'ABSENT_UNKNOWN')),
    ADD CONSTRAINT ck_worker_codex_update_plan_recovery_shape
        CHECK ((plan_kind = 'STANDARD' AND previous_bootstrap_state = 'PRESENT')
            OR (plan_kind = 'RECOVERY'
                AND previous_bootstrap_state = 'ABSENT_UNKNOWN'
                AND previous_inventory_id IS NULL));

CREATE TABLE worker_codex_release_reconciliation (
    reconciliation_id UUID PRIMARY KEY,
    worker_id VARCHAR(80) NOT NULL,
    requested_by BIGINT NOT NULL,
    idempotency_key UUID NOT NULL,
    plan_id UUID NOT NULL,
    current_inventory_id UUID NOT NULL,
    candidate_inventory_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    previous_state VARCHAR(16) NOT NULL,
    previous_compatibility_state VARCHAR(24) NOT NULL,
    structure_verification_gate VARCHAR(16) NOT NULL,
    permission_verification_gate VARCHAR(16) NOT NULL,
    metadata_verification_gate VARCHAR(16) NOT NULL,
    version_verification_gate VARCHAR(16) NOT NULL,
    hash_verification_gate VARCHAR(16) NOT NULL,
    zero_non_terminal_runs_gate VARCHAR(16) NOT NULL,
    current_link_fingerprint VARCHAR(64) NOT NULL,
    inventory_sha256 VARCHAR(64) NOT NULL,
    plan_sha256 VARCHAR(64) NOT NULL,
    registry_sha256 VARCHAR(64) NOT NULL,
    links_changed BOOLEAN NOT NULL,
    values_exposed BOOLEAN NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_worker_codex_reconcile_worker
        FOREIGN KEY (worker_id) REFERENCES worker_node (id) ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_reconcile_operator
        FOREIGN KEY (requested_by) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_reconcile_plan
        FOREIGN KEY (plan_id) REFERENCES worker_codex_update_plan (plan_id) ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_reconcile_current
        FOREIGN KEY (worker_id, current_inventory_id)
        REFERENCES worker_codex_release_inventory (worker_id, inventory_id) ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_reconcile_candidate
        FOREIGN KEY (worker_id, candidate_inventory_id)
        REFERENCES worker_codex_release_inventory (worker_id, inventory_id) ON DELETE RESTRICT,
    CONSTRAINT uk_worker_codex_reconcile_idempotency
        UNIQUE (requested_by, idempotency_key),
    CONSTRAINT uk_worker_codex_reconcile_plan UNIQUE (plan_id),
    CONSTRAINT ck_worker_codex_reconcile_state CHECK (state = 'RECONCILED'),
    CONSTRAINT ck_worker_codex_reconcile_previous
        CHECK (previous_state = 'ABSENT' AND previous_compatibility_state = 'UNKNOWN'),
    CONSTRAINT ck_worker_codex_reconcile_gates
        CHECK (structure_verification_gate = 'PASS'
            AND permission_verification_gate = 'PASS'
            AND metadata_verification_gate = 'PASS'
            AND version_verification_gate = 'PASS'
            AND hash_verification_gate = 'PASS'
            AND zero_non_terminal_runs_gate = 'PASS'),
    CONSTRAINT ck_worker_codex_reconcile_digests
        CHECK (current_link_fingerprint ~ '^[0-9a-f]{64}$'
            AND inventory_sha256 ~ '^[0-9a-f]{64}$'
            AND plan_sha256 ~ '^[0-9a-f]{64}$'
            AND registry_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_worker_codex_reconcile_no_values CHECK (values_exposed = FALSE)
);

CREATE INDEX idx_worker_codex_reconcile_worker_created
    ON worker_codex_release_reconciliation (worker_id, created_at DESC);
