-- Observational metadata only. Legacy evidence stays explicitly unknown;
-- no backfill invents a worker phase or a confirmation timestamp.
ALTER TABLE validation_operation
    ADD COLUMN worker_state VARCHAR(32),
    ADD COLUMN worker_observed_at TIMESTAMPTZ,
    ADD COLUMN worker_started_at TIMESTAMPTZ,
    ADD CONSTRAINT validation_worker_progress_pair CHECK (
        (worker_state IS NULL AND worker_observed_at IS NULL AND worker_started_at IS NULL)
        OR (worker_state IS NOT NULL AND worker_observed_at IS NOT NULL)
    ),
    ADD CONSTRAINT validation_worker_progress_state CHECK (worker_state IN (
        'QUEUED', 'RUNNING', 'CANCELLING', 'RECONCILING', 'SUCCEEDED',
        'CANDIDATE_FAILED', 'INFRASTRUCTURE_FAILED', 'POLICY_FAILED',
        'VALIDATION_FAILED', 'OWNERSHIP_FAILED', 'CANCELLED'
    ));
