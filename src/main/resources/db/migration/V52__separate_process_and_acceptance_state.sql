ALTER TABLE agent_run
    ADD COLUMN process_outcome VARCHAR(16);

UPDATE agent_run
SET process_outcome = status
WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED');

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_process_outcome
        CHECK (
            (status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLING', 'RECONCILING')
                AND process_outcome IS NULL)
            OR (status = 'SUCCEEDED' AND process_outcome = 'SUCCEEDED')
            OR (status = 'FAILED' AND process_outcome = 'FAILED')
            OR (status = 'CANCELLED' AND process_outcome = 'CANCELLED')
        );

ALTER TABLE work_session
    ADD COLUMN acceptance_state VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN source_tree_fingerprint_sha256 VARCHAR(64),
    ADD COLUMN source_tree_observed_at TIMESTAMPTZ,
    ADD COLUMN validation_projection_sha256 VARCHAR(64),
    ADD COLUMN validation_definition_revision VARCHAR(80),
    ADD COLUMN acceptance_blocked_check VARCHAR(80),
    ADD COLUMN acceptance_next_action VARCHAR(240),
    ADD COLUMN validated_at TIMESTAMPTZ,
    ADD COLUMN integration_ready_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_work_session_acceptance_state
        CHECK (acceptance_state IN (
            'DRAFT', 'VALIDATING', 'BLOCKED', 'VALIDATED', 'INTEGRATION_READY'
        )),
    ADD CONSTRAINT ck_work_session_source_tree_fingerprint
        CHECK (
            (source_tree_fingerprint_sha256 IS NULL AND source_tree_observed_at IS NULL)
            OR (
                source_tree_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
                AND source_tree_observed_at IS NOT NULL
            )
        ),
    ADD CONSTRAINT ck_work_session_acceptance_projection
        CHECK (
            (acceptance_state = 'DRAFT'
                AND validation_projection_sha256 IS NULL
                AND validation_definition_revision IS NULL
                AND acceptance_blocked_check IS NULL
                AND validated_at IS NULL
                AND integration_ready_at IS NULL)
            OR (acceptance_state = 'VALIDATING'
                AND source_tree_fingerprint_sha256 IS NOT NULL
                AND validation_projection_sha256 ~ '^[0-9a-f]{64}$'
                AND validation_definition_revision IS NOT NULL
                AND acceptance_blocked_check IS NULL
                AND validated_at IS NULL
                AND integration_ready_at IS NULL)
            OR (acceptance_state = 'BLOCKED'
                AND source_tree_fingerprint_sha256 IS NOT NULL
                AND validation_projection_sha256 ~ '^[0-9a-f]{64}$'
                AND validation_definition_revision IS NOT NULL
                AND acceptance_blocked_check IS NOT NULL
                AND acceptance_next_action IS NOT NULL
                AND validated_at IS NULL
                AND integration_ready_at IS NULL)
            OR (acceptance_state = 'VALIDATED'
                AND source_tree_fingerprint_sha256 IS NOT NULL
                AND validation_projection_sha256 ~ '^[0-9a-f]{64}$'
                AND validation_definition_revision IS NOT NULL
                AND acceptance_blocked_check IS NULL
                AND validated_at IS NOT NULL
                AND integration_ready_at IS NULL)
            OR (acceptance_state = 'INTEGRATION_READY'
                AND source_tree_fingerprint_sha256 IS NOT NULL
                AND validation_projection_sha256 ~ '^[0-9a-f]{64}$'
                AND validation_definition_revision IS NOT NULL
                AND acceptance_blocked_check IS NULL
                AND validated_at IS NOT NULL
                AND integration_ready_at IS NOT NULL)
        );
