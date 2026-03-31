CREATE TABLE core_command_event (
    id BIGSERIAL PRIMARY KEY,
    command_id BIGINT NOT NULL,
    phase VARCHAR(64) NOT NULL,
    message TEXT NOT NULL,
    payload_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_core_command_event_command
        FOREIGN KEY (command_id) REFERENCES core_command (id) ON DELETE CASCADE
);

CREATE INDEX idx_core_command_event_command_created_at
    ON core_command_event (command_id, created_at DESC);
