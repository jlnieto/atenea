CREATE TABLE mobile_push_notification_log (
    id BIGSERIAL PRIMARY KEY,
    event_key VARCHAR(255) NOT NULL UNIQUE,
    event_type VARCHAR(64) NOT NULL,
    session_id BIGINT,
    run_id BIGINT,
    deliverable_id BIGINT,
    title VARCHAR(190) NOT NULL,
    body TEXT,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_mobile_push_notification_log_session_id ON mobile_push_notification_log(session_id);
CREATE INDEX idx_mobile_push_notification_log_run_id ON mobile_push_notification_log(run_id);
CREATE INDEX idx_mobile_push_notification_log_deliverable_id ON mobile_push_notification_log(deliverable_id);
