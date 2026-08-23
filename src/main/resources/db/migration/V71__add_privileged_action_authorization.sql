ALTER TABLE operator_auth_attempt_window
    DROP CONSTRAINT ck_operator_auth_attempt_scope,
    ADD CONSTRAINT ck_operator_auth_attempt_scope
        CHECK (scope IN ('TOTP_ENROLLMENT', 'TOTP_REMOVAL', 'RECOVERY', 'STEP_UP'));

ALTER TABLE operator_webauthn_challenge
    ADD COLUMN action_kind VARCHAR(64),
    ADD COLUMN target_fingerprint BYTEA,
    ADD COLUMN plan_fingerprint BYTEA,
    DROP CONSTRAINT ck_operator_webauthn_challenge_purpose,
    DROP CONSTRAINT ck_operator_webauthn_challenge_binding,
    ADD CONSTRAINT ck_operator_webauthn_challenge_purpose
        CHECK (purpose IN ('REGISTRATION', 'AUTHENTICATION', 'STEP_UP')),
    ADD CONSTRAINT ck_operator_webauthn_challenge_action
        CHECK ((purpose = 'STEP_UP'
                AND action_kind IS NOT NULL
                AND target_fingerprint IS NOT NULL
                AND plan_fingerprint IS NOT NULL
                AND action_kind ~ '^[A-Z][A-Z0-9_]{2,63}$'
                AND octet_length(target_fingerprint) = 32
                AND octet_length(plan_fingerprint) = 32)
            OR (purpose <> 'STEP_UP'
                AND action_kind IS NULL
                AND target_fingerprint IS NULL
                AND plan_fingerprint IS NULL)),
    ADD CONSTRAINT ck_operator_webauthn_challenge_binding
        CHECK ((purpose IN ('REGISTRATION', 'STEP_UP')
                AND operator_id IS NOT NULL
                AND session_family_id IS NOT NULL)
            OR (purpose = 'AUTHENTICATION'
                AND operator_id IS NULL
                AND session_family_id IS NULL));

CREATE TABLE operator_privileged_action_authorization (
    id UUID PRIMARY KEY,
    authorization_digest BYTEA NOT NULL UNIQUE,
    operator_id BIGINT NOT NULL,
    session_family_id UUID NOT NULL,
    action_kind VARCHAR(64) NOT NULL,
    target_fingerprint BYTEA NOT NULL,
    plan_fingerprint BYTEA NOT NULL,
    factor VARCHAR(16) NOT NULL,
    authenticated_at TIMESTAMPTZ NOT NULL,
    credential_version BIGINT NOT NULL,
    role_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_operator_action_authorization_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_operator_action_authorization_family
        FOREIGN KEY (session_family_id) REFERENCES operator_session_family (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operator_action_authorization_digest
        CHECK (octet_length(authorization_digest) = 32),
    CONSTRAINT ck_operator_action_authorization_kind
        CHECK (action_kind ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    CONSTRAINT ck_operator_action_authorization_fingerprints
        CHECK (octet_length(target_fingerprint) = 32
            AND octet_length(plan_fingerprint) = 32),
    CONSTRAINT ck_operator_action_authorization_factor
        CHECK (factor IN ('WEBAUTHN', 'TOTP')),
    CONSTRAINT ck_operator_action_authorization_versions
        CHECK (credential_version >= 0 AND role_version >= 0),
    CONSTRAINT ck_operator_action_authorization_timestamps
        CHECK (expires_at > created_at
            AND authenticated_at <= created_at
            AND (consumed_at IS NULL OR consumed_at >= created_at))
);

CREATE INDEX idx_operator_action_authorization_live
    ON operator_privileged_action_authorization (operator_id, session_family_id, expires_at, id)
    WHERE consumed_at IS NULL;
