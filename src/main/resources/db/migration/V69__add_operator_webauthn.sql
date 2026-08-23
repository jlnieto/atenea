ALTER TABLE operator_session_family
    ADD COLUMN authenticated_at TIMESTAMPTZ,
    ADD COLUMN authentication_method VARCHAR(20),
    ADD CONSTRAINT ck_operator_session_family_authentication
        CHECK ((authenticated_at IS NULL AND authentication_method IS NULL)
            OR (authenticated_at IS NOT NULL
                AND authenticated_at <= created_at
                AND authentication_method IN ('pwd', 'webauthn')));

CREATE TABLE operator_webauthn_user (
    operator_id BIGINT PRIMARY KEY,
    user_handle BYTEA NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_operator_webauthn_user_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operator_webauthn_user_handle
        CHECK (octet_length(user_handle) = 32),
    CONSTRAINT ck_operator_webauthn_user_timestamps
        CHECK (updated_at >= created_at)
);

CREATE TABLE operator_webauthn_credential (
    id UUID PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    credential_id BYTEA NOT NULL UNIQUE,
    public_key_cose BYTEA NOT NULL,
    algorithm INTEGER NOT NULL,
    aaguid UUID NOT NULL,
    sign_count BIGINT NOT NULL DEFAULT 0,
    transports VARCHAR(200) NOT NULL DEFAULT '',
    backup_eligible BOOLEAN NOT NULL,
    backup_state BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(40),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_operator_webauthn_credential_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operator_webauthn_credential_id
        CHECK (octet_length(credential_id) BETWEEN 16 AND 1024),
    CONSTRAINT ck_operator_webauthn_public_key
        CHECK (octet_length(public_key_cose) BETWEEN 16 AND 4096),
    CONSTRAINT ck_operator_webauthn_algorithm
        CHECK (algorithm IN (-7, -8, -257)),
    CONSTRAINT ck_operator_webauthn_counter
        CHECK (sign_count >= 0),
    CONSTRAINT ck_operator_webauthn_transports
        CHECK (transports = '' OR transports ~ '^[a-z]+(,[a-z]+)*$'),
    CONSTRAINT ck_operator_webauthn_backup
        CHECK (NOT backup_state OR backup_eligible),
    CONSTRAINT ck_operator_webauthn_credential_timestamps
        CHECK ((last_used_at IS NULL OR last_used_at >= created_at)
            AND (revoked_at IS NULL OR revoked_at >= created_at)),
    CONSTRAINT ck_operator_webauthn_revocation
        CHECK ((revoked_at IS NULL AND revocation_reason IS NULL)
            OR (revoked_at IS NOT NULL
                AND revocation_reason ~ '^[A-Z][A-Z0-9_]{2,39}$'))
);

CREATE INDEX idx_operator_webauthn_credential_active
    ON operator_webauthn_credential (operator_id, created_at, id)
    WHERE revoked_at IS NULL;

CREATE TABLE operator_webauthn_challenge (
    id UUID PRIMARY KEY,
    challenge_digest BYTEA NOT NULL UNIQUE,
    purpose VARCHAR(20) NOT NULL,
    channel VARCHAR(12) NOT NULL,
    operator_id BIGINT,
    session_family_id UUID,
    relying_party_id VARCHAR(253) NOT NULL,
    expected_origin VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_operator_webauthn_challenge_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_operator_webauthn_challenge_family
        FOREIGN KEY (session_family_id) REFERENCES operator_session_family (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operator_webauthn_challenge_digest
        CHECK (octet_length(challenge_digest) = 32),
    CONSTRAINT ck_operator_webauthn_challenge_purpose
        CHECK (purpose IN ('REGISTRATION', 'AUTHENTICATION')),
    CONSTRAINT ck_operator_webauthn_challenge_channel
        CHECK (channel IN ('WEB', 'ANDROID')),
    CONSTRAINT ck_operator_webauthn_challenge_rp
        CHECK (relying_party_id ~ '^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$'),
    CONSTRAINT ck_operator_webauthn_challenge_origin
        CHECK (expected_origin = btrim(expected_origin)
            AND expected_origin !~ '[[:space:]]'),
    CONSTRAINT ck_operator_webauthn_challenge_timestamps
        CHECK (expires_at > created_at
            AND (consumed_at IS NULL OR consumed_at >= created_at)),
    CONSTRAINT ck_operator_webauthn_challenge_binding
        CHECK ((purpose = 'REGISTRATION'
                AND operator_id IS NOT NULL
                AND session_family_id IS NOT NULL)
            OR (purpose = 'AUTHENTICATION'
                AND operator_id IS NULL
                AND session_family_id IS NULL))
);

CREATE INDEX idx_operator_webauthn_challenge_live
    ON operator_webauthn_challenge (purpose, expires_at, id)
    WHERE consumed_at IS NULL;
