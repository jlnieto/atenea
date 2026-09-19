CREATE TABLE worker_codex_recovery_activation (
    activation_id UUID PRIMARY KEY,
    worker_id VARCHAR(80) NOT NULL REFERENCES worker_node (id) ON DELETE RESTRICT,
    requested_by BIGINT NOT NULL REFERENCES operator_account (id) ON DELETE RESTRICT,
    idempotency_key UUID NOT NULL,
    reconciliation_id UUID NOT NULL REFERENCES worker_codex_release_reconciliation (reconciliation_id) ON DELETE RESTRICT,
    plan_id UUID NOT NULL REFERENCES worker_codex_update_plan (plan_id) ON DELETE RESTRICT,
    current_inventory_id UUID NOT NULL,
    candidate_inventory_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVATED', 'RESTORED')),
    automatic_restore VARCHAR(16) NOT NULL CHECK (automatic_restore IN ('NOT_REQUIRED', 'PASS')),
    version_gate VARCHAR(16),
    catalog_gate VARCHAR(16),
    worker_health_gate VARCHAR(16),
    fixed_canary_gate VARCHAR(16),
    zero_non_terminal_runs_gate VARCHAR(16),
    current_before_fingerprint VARCHAR(64),
    current_after_fingerprint VARCHAR(64),
    previous_after_fingerprint VARCHAR(64),
    inventory_sha256 VARCHAR(64) NOT NULL,
    plan_sha256 VARCHAR(64) NOT NULL,
    values_exposed BOOLEAN NOT NULL CHECK (values_exposed = FALSE),
    completed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_worker_codex_recovery_activation_idempotency UNIQUE (requested_by, idempotency_key),
    CONSTRAINT uk_worker_codex_recovery_activation_plan UNIQUE (plan_id),
    CONSTRAINT fk_worker_codex_recovery_activation_current
        FOREIGN KEY (worker_id, current_inventory_id)
        REFERENCES worker_codex_release_inventory (worker_id, inventory_id) ON DELETE RESTRICT,
    CONSTRAINT fk_worker_codex_recovery_activation_candidate
        FOREIGN KEY (worker_id, candidate_inventory_id)
        REFERENCES worker_codex_release_inventory (worker_id, inventory_id) ON DELETE RESTRICT,
    CONSTRAINT ck_worker_codex_recovery_activation_digests
        CHECK (inventory_sha256 ~ '^[0-9a-f]{64}$' AND plan_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_worker_codex_recovery_activation_gates
        CHECK (state = 'RESTORED' OR
               (version_gate = 'PASS' AND catalog_gate = 'PASS'
                AND worker_health_gate = 'PASS' AND fixed_canary_gate = 'PASS'
                AND zero_non_terminal_runs_gate = 'PASS'
                AND automatic_restore = 'NOT_REQUIRED'
                AND current_before_fingerprint ~ '^[0-9a-f]{64}$'
                AND current_after_fingerprint ~ '^[0-9a-f]{64}$'
                AND previous_after_fingerprint ~ '^[0-9a-f]{64}$'))
);
