ALTER TABLE operator_account
    ADD COLUMN factor_reenrollment_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE operator_totp_factor (
    id UUID PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    enrollment_id UUID NOT NULL UNIQUE,
    encrypted_secret BYTEA NOT NULL,
    secret_key_version VARCHAR(32) NOT NULL,
    state VARCHAR(16) NOT NULL,
    last_accepted_counter BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(40),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_operator_totp_factor_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operator_totp_factor_secret
        CHECK (octet_length(encrypted_secret) BETWEEN 44 AND 128),
    CONSTRAINT ck_operator_totp_factor_key_version
        CHECK (secret_key_version ~ '^[a-z0-9][a-z0-9_-]{0,31}$'),
    CONSTRAINT ck_operator_totp_factor_state
        CHECK (state IN ('PENDING', 'ACTIVE', 'CANCELLED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_operator_totp_factor_counter
        CHECK (last_accepted_counter IS NULL OR last_accepted_counter >= 0),
    CONSTRAINT ck_operator_totp_factor_timestamps
        CHECK (expires_at > created_at
            AND (activated_at IS NULL OR activated_at >= created_at)
            AND (revoked_at IS NULL OR revoked_at >= created_at)),
    CONSTRAINT ck_operator_totp_factor_projection
        CHECK ((state = 'PENDING' AND activated_at IS NULL AND revoked_at IS NULL
                    AND revocation_reason IS NULL)
            OR (state = 'ACTIVE' AND activated_at IS NOT NULL AND revoked_at IS NULL
                    AND revocation_reason IS NULL)
            OR (state IN ('CANCELLED', 'EXPIRED', 'REVOKED')
                    AND revoked_at IS NOT NULL
                    AND revocation_reason ~ '^[A-Z][A-Z0-9_]{2,39}$'))
);

CREATE UNIQUE INDEX uk_operator_totp_factor_pending
    ON operator_totp_factor (operator_id) WHERE state = 'PENDING';
CREATE UNIQUE INDEX uk_operator_totp_factor_active
    ON operator_totp_factor (operator_id) WHERE state = 'ACTIVE';

CREATE TABLE operator_recovery_code (
    id UUID PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    factor_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    code_hmac BYTEA NOT NULL UNIQUE,
    hmac_key_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(40),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_operator_recovery_code_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_operator_recovery_code_factor
        FOREIGN KEY (factor_id) REFERENCES operator_totp_factor (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operator_recovery_code_hmac
        CHECK (octet_length(code_hmac) = 32),
    CONSTRAINT ck_operator_recovery_code_key_version
        CHECK (hmac_key_version ~ '^[a-z0-9][a-z0-9_-]{0,31}$'),
    CONSTRAINT ck_operator_recovery_code_timestamps
        CHECK ((consumed_at IS NULL OR consumed_at >= created_at)
            AND (revoked_at IS NULL OR revoked_at >= created_at)),
    CONSTRAINT ck_operator_recovery_code_terminal
        CHECK (NOT (consumed_at IS NOT NULL AND revoked_at IS NOT NULL)
            AND ((revoked_at IS NULL AND revocation_reason IS NULL)
                OR (revoked_at IS NOT NULL
                    AND revocation_reason ~ '^[A-Z][A-Z0-9_]{2,39}$')))
);

CREATE INDEX idx_operator_recovery_code_active
    ON operator_recovery_code (operator_id, batch_id, id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE TABLE operator_auth_attempt_window (
    id UUID PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    scope VARCHAR(32) NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    failed_count INTEGER NOT NULL,
    blocked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_operator_auth_attempt_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT uk_operator_auth_attempt_scope UNIQUE (operator_id, scope),
    CONSTRAINT ck_operator_auth_attempt_scope
        CHECK (scope IN ('TOTP_ENROLLMENT', 'TOTP_REMOVAL', 'RECOVERY')),
    CONSTRAINT ck_operator_auth_attempt_count CHECK (failed_count BETWEEN 0 AND 1000),
    CONSTRAINT ck_operator_auth_attempt_timestamps
        CHECK (updated_at >= window_started_at
            AND (blocked_until IS NULL OR blocked_until >= window_started_at))
);

CREATE TABLE operator_security_event (
    id UUID PRIMARY KEY,
    operator_id BIGINT,
    event_type VARCHAR(40) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_operator_security_event_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operator_security_event_type
        CHECK (event_type ~ '^[A-Z][A-Z0-9_]{2,39}$'),
    CONSTRAINT ck_operator_security_event_outcome
        CHECK (outcome IN ('SUCCEEDED', 'REJECTED', 'RATE_LIMITED'))
);

CREATE INDEX idx_operator_security_event_actor
    ON operator_security_event (operator_id, occurred_at, id);

CREATE TABLE operator_security_notification (
    id UUID PRIMARY KEY,
    security_event_id UUID NOT NULL UNIQUE,
    operator_id BIGINT NOT NULL,
    template_code VARCHAR(40) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_operator_security_notification_event
        FOREIGN KEY (security_event_id) REFERENCES operator_security_event (id) ON DELETE RESTRICT,
    CONSTRAINT fk_operator_security_notification_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operator_security_notification_template
        CHECK (template_code IN ('ACCOUNT_RECOVERED')),
    CONSTRAINT ck_operator_security_notification_state
        CHECK (state IN ('PENDING', 'DELIVERED', 'FAILED'))
);
