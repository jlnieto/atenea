CREATE TABLE notification_event (
    id UUID PRIMARY KEY,
    deduplication_sha256 VARCHAR(64) NOT NULL UNIQUE,
    category VARCHAR(32) NOT NULL,
    template_version VARCHAR(40) NOT NULL,
    deep_link_kind VARCHAR(40) NOT NULL,
    session_id BIGINT NOT NULL,
    agent_run_id BIGINT NOT NULL,
    source_revision BIGINT NOT NULL,
    safe_title VARCHAR(120) NOT NULL,
    safe_body VARCHAR(190) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_notification_event_session
        FOREIGN KEY (session_id) REFERENCES work_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_event_owned_run
        FOREIGN KEY (agent_run_id, session_id)
        REFERENCES agent_run (id, session_id) ON DELETE CASCADE,
    CONSTRAINT uk_notification_event_source
        UNIQUE (category, agent_run_id, source_revision),
    CONSTRAINT ck_notification_event_digest
        CHECK (deduplication_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_notification_event_category
        CHECK (category IN ('RUN_COMPLETED', 'RUN_FAILED', 'ACTION_REQUIRED')),
    CONSTRAINT ck_notification_event_revision
        CHECK (source_revision >= 0),
    CONSTRAINT ck_notification_event_template
        CHECK (template_version = 'agent-run-safe-v1'
            AND deep_link_kind = 'WORK_SESSION_CONVERSATION'),
    CONSTRAINT ck_notification_event_safe_copy
        CHECK (
            (category = 'RUN_COMPLETED'
                AND safe_title = 'Tarea completada'
                AND safe_body = 'Abre Atenea para revisar el resultado')
            OR (category = 'RUN_FAILED'
                AND safe_title = 'La tarea necesita atención'
                AND safe_body = 'Abre Atenea para revisar el fallo y el siguiente paso')
            OR (category = 'ACTION_REQUIRED'
                AND safe_title = 'Se necesita una acción'
                AND safe_body = 'Abre Atenea para continuar esta sesión')
        )
);

CREATE TABLE notification_preference (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_notification_preference_device
        FOREIGN KEY (device_id) REFERENCES operator_push_device (id) ON DELETE CASCADE,
    CONSTRAINT uk_notification_preference_device_category
        UNIQUE (device_id, category),
    CONSTRAINT ck_notification_preference_category
        CHECK (category IN ('RUN_COMPLETED', 'RUN_FAILED', 'ACTION_REQUIRED'))
);

CREATE TABLE notification_delivery (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    device_id BIGINT NOT NULL,
    channel VARCHAR(16) NOT NULL,
    state VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    diagnostic_code VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_notification_delivery_event
        FOREIGN KEY (event_id) REFERENCES notification_event (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_delivery_device
        FOREIGN KEY (device_id) REFERENCES operator_push_device (id) ON DELETE CASCADE,
    CONSTRAINT uk_notification_delivery_owner
        UNIQUE (event_id, device_id, channel),
    CONSTRAINT ck_notification_delivery_channel
        CHECK (channel = 'FCM'),
    CONSTRAINT ck_notification_delivery_state
        CHECK (state IN (
            'PENDING', 'SENDING', 'RETRY_WAIT', 'DELIVERED',
            'EXPIRED', 'INVALID_TOKEN', 'FAILED'
        )),
    CONSTRAINT ck_notification_delivery_attempts
        CHECK (attempt_count BETWEEN 0 AND 5),
    CONSTRAINT ck_notification_delivery_lifecycle
        CHECK (
            (state = 'PENDING' AND attempt_count = 0
                AND next_attempt_at IS NULL AND delivered_at IS NULL
                AND diagnostic_code IS NULL)
            OR (state = 'SENDING' AND attempt_count BETWEEN 1 AND 5
                AND next_attempt_at IS NULL AND delivered_at IS NULL)
            OR (state = 'RETRY_WAIT' AND attempt_count BETWEEN 1 AND 4
                AND next_attempt_at IS NOT NULL AND delivered_at IS NULL
                AND diagnostic_code IS NOT NULL)
            OR (state = 'DELIVERED' AND attempt_count BETWEEN 1 AND 5
                AND next_attempt_at IS NULL AND delivered_at IS NOT NULL
                AND diagnostic_code IS NULL)
            OR (state IN ('EXPIRED', 'INVALID_TOKEN', 'FAILED')
                AND next_attempt_at IS NULL AND delivered_at IS NULL
                AND diagnostic_code IS NOT NULL)
        ),
    CONSTRAINT ck_notification_delivery_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_notification_delivery_dispatch
    ON notification_delivery (state, next_attempt_at, created_at);
CREATE INDEX idx_notification_delivery_device
    ON notification_delivery (device_id, created_at DESC);
