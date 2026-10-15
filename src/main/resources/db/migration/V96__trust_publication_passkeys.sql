-- Login/registration alone is not authority to approve a production release.
-- Existing credentials deliberately receive no automatic trust/backfill.
ALTER TABLE operator_webauthn_credential
    ADD CONSTRAINT uk_webauthn_credential_owner UNIQUE (id, operator_id);
ALTER TABLE operator_privileged_action_authorization
    ADD CONSTRAINT uk_action_authorization_owner UNIQUE (id, operator_id);

CREATE TABLE operator_release_passkey_trust (
    credential_record_id UUID PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    credential_fingerprint_sha256 VARCHAR(64) NOT NULL
        CHECK (credential_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    authorization_id UUID NOT NULL UNIQUE,
    verification_request_id UUID NOT NULL UNIQUE
        REFERENCES operator_webauthn_challenge(id) ON DELETE RESTRICT,
    trusted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_release_passkey_credential_owner FOREIGN KEY (credential_record_id, operator_id)
        REFERENCES operator_webauthn_credential(id, operator_id) ON DELETE RESTRICT,
    CONSTRAINT fk_release_passkey_authorization_owner FOREIGN KEY (authorization_id, operator_id)
        REFERENCES operator_privileged_action_authorization(id, operator_id) ON DELETE RESTRICT
);
COMMENT ON TABLE operator_release_passkey_trust IS
    'Explicit TOTP-approved passkeys for publication step-up, bound to the immutable credential and consumed authorization; never login-only trust.';
