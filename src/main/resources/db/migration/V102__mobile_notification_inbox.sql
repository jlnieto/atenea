CREATE TABLE mobile_notification_inbox (
    id UUID PRIMARY KEY,
    operator_id BIGINT NOT NULL REFERENCES operator_account(id) ON DELETE CASCADE,
    event_key VARCHAR(255) NOT NULL,
    category VARCHAR(64) NOT NULL,
    title VARCHAR(190) NOT NULL,
    body VARCHAR(500) NOT NULL,
    destination VARCHAR(32) NOT NULL CHECK (destination IN ('CONVERSATION', 'OPERATIONS', 'UPDATES')),
    target_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    CONSTRAINT uk_mobile_notification_inbox_event UNIQUE (operator_id, event_key),
    CONSTRAINT ck_mobile_notification_inbox_target CHECK (
        (destination = 'UPDATES' AND target_id IS NULL) OR
        (destination <> 'UPDATES' AND target_id > 0)
    )
);
CREATE INDEX idx_mobile_notification_inbox_recent ON mobile_notification_inbox(operator_id, created_at DESC);
