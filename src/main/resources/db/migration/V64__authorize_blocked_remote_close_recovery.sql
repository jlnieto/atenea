ALTER TABLE remote_close_legacy_plan
    ADD COLUMN consumed_at TIMESTAMPTZ,
    ADD COLUMN consumed_by_operation_id UUID,
    ADD COLUMN confirmation_idempotency_key UUID;

UPDATE remote_close_legacy_plan plan
SET consumed_at = operation.requested_at,
    consumed_by_operation_id = operation.operation_id,
    confirmation_idempotency_key = operation.idempotency_key
FROM remote_close_legacy_operation operation
WHERE operation.plan_id = plan.plan_id;

ALTER TABLE remote_close_legacy_plan
    ADD CONSTRAINT fk_remote_close_legacy_plan_consumed_operation
        FOREIGN KEY (consumed_by_operation_id)
        REFERENCES remote_close_legacy_operation (operation_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_remote_close_legacy_plan_consumption
        CHECK (
            (consumed_at IS NULL
                AND consumed_by_operation_id IS NULL
                AND confirmation_idempotency_key IS NULL)
            OR (consumed_at IS NOT NULL
                AND consumed_at >= created_at
                AND consumed_by_operation_id IS NOT NULL
                AND confirmation_idempotency_key IS NOT NULL)
        ),
    ADD CONSTRAINT uk_remote_close_legacy_plan_confirmation_idempotency
        UNIQUE (requested_by, confirmation_idempotency_key);

CREATE INDEX idx_remote_close_legacy_plan_consumed_operation
    ON remote_close_legacy_plan (consumed_by_operation_id, consumed_at DESC)
    WHERE consumed_by_operation_id IS NOT NULL;
