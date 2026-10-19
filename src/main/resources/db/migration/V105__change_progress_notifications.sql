-- Progress notifications do not impersonate AgentRuns or authorize delivery effects.
CREATE TABLE mobile_change_progress_notice (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL REFERENCES mobile_delivery_operation(id) ON DELETE CASCADE,
    outcome varchar(32) NOT NULL CHECK (outcome IN
        ('INTEGRATION_READY','READY','SUCCEEDED','BLOCKED','FAILED','ROLLED_BACK','ROLLBACK_FAILED','QUARANTINED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(operation_id, outcome)
);
CREATE TABLE mobile_change_progress_delivery (
    id uuid PRIMARY KEY,
    notice_id uuid NOT NULL REFERENCES mobile_change_progress_notice(id) ON DELETE CASCADE,
    device_id bigint NOT NULL REFERENCES operator_push_device(id) ON DELETE CASCADE,
    title varchar(190) NOT NULL,
    body varchar(500) NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','SENDING','ACCEPTED','EXPIRED','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    claim_token uuid,
    diagnostic_code varchar(80),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT now()+interval '10 minutes',
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(notice_id, device_id)
);
CREATE INDEX mobile_change_progress_pending ON mobile_change_progress_delivery(next_attempt_at)
    WHERE state IN ('PENDING','SENDING');
