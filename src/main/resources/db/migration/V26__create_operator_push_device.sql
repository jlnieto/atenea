CREATE TABLE operator_push_device (
    id BIGSERIAL PRIMARY KEY,
    operator_id BIGINT NOT NULL REFERENCES operator_account(id) ON DELETE CASCADE,
    expo_push_token VARCHAR(255) NOT NULL UNIQUE,
    device_id VARCHAR(190),
    device_name VARCHAR(190),
    platform VARCHAR(32) NOT NULL,
    app_version VARCHAR(64),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_operator_push_device_operator_id ON operator_push_device(operator_id);
CREATE INDEX idx_operator_push_device_active ON operator_push_device(active);
