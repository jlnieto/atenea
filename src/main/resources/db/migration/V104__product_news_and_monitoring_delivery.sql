-- Read model only. Never accepted as publication or admission authority.
CREATE TABLE atenea_updates_snapshot (
    page integer PRIMARY KEY CHECK(page BETWEEN 1 AND 50),
    payload_json jsonb CHECK(payload_json IS NULL OR jsonb_typeof(payload_json)='object'),
    attempted_at timestamptz NOT NULL,
    error_code varchar(64)
);
CREATE TABLE operations_monitor_state (
    host_id bigint PRIMARY KEY REFERENCES managed_host(id),
    last_check_run_id bigint REFERENCES operations_action_run(id),
    issue_state_json jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(issue_state_json)='object'),
    observed_at timestamptz
);
CREATE TABLE operations_alert_delivery (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL,
    check_run_id bigint NOT NULL REFERENCES operations_action_run(id),
    host_id bigint NOT NULL REFERENCES managed_host(id),
    device_id bigint NOT NULL REFERENCES operator_push_device(id),
    kind varchar(48) NOT NULL CHECK(kind IN ('OPERATIONS_DEGRADATION','OPERATIONS_VERIFICATION_FAILURE')),
    title varchar(190) NOT NULL,
    body varchar(500) NOT NULL,
    state varchar(24) NOT NULL CHECK(state IN ('PENDING','SENDING','RETRY_WAIT','DELIVERED','FAILED','INVALID_TOKEN','EXPIRED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 5),
    next_attempt_at timestamptz,
    lease_until timestamptz,
    diagnostic_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    UNIQUE(check_run_id,kind,device_id)
);
CREATE INDEX operations_alert_pending ON operations_alert_delivery(updated_at)
    WHERE state IN ('PENDING','RETRY_WAIT','SENDING');
