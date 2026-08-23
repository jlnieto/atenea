ALTER TABLE operator_webauthn_credential
    ADD COLUMN provider_category VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN provider_provenance VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN label_ordinal BIGINT,
    ADD COLUMN last_verified_at TIMESTAMPTZ;

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY operator_id
               ORDER BY created_at, id
           ) AS ordinal
    FROM operator_webauthn_credential
)
UPDATE operator_webauthn_credential credential
SET label_ordinal = ranked.ordinal
FROM ranked
WHERE ranked.id = credential.id;

ALTER TABLE operator_webauthn_credential
    ALTER COLUMN label_ordinal SET NOT NULL,
    ADD CONSTRAINT uq_operator_webauthn_credential_label
        UNIQUE (operator_id, label_ordinal),
    ADD CONSTRAINT ck_operator_webauthn_provider_category
        CHECK (provider_category IN (
            'GOOGLE_PASSWORD_MANAGER',
            'ONE_PASSWORD',
            'HARDWARE_SECURITY_KEY',
            'OTHER',
            'UNKNOWN'
        )),
    ADD CONSTRAINT ck_operator_webauthn_provider_provenance
        CHECK (provider_provenance IN ('OPERATOR_DECLARED', 'UNKNOWN')),
    ADD CONSTRAINT ck_operator_webauthn_label_ordinal
        CHECK (label_ordinal > 0),
    ADD CONSTRAINT ck_operator_webauthn_last_verified
        CHECK (last_verified_at IS NULL OR last_verified_at >= created_at);

CREATE INDEX idx_operator_webauthn_credential_inventory
    ON operator_webauthn_credential (operator_id, label_ordinal, id);

ALTER TABLE operator_webauthn_challenge
    DROP CONSTRAINT ck_operator_webauthn_challenge_purpose,
    DROP CONSTRAINT ck_operator_webauthn_challenge_binding,
    ADD CONSTRAINT ck_operator_webauthn_challenge_purpose
        CHECK (purpose IN ('REGISTRATION', 'AUTHENTICATION', 'OWNERSHIP', 'STEP_UP')),
    ADD CONSTRAINT ck_operator_webauthn_challenge_binding
        CHECK ((purpose IN ('REGISTRATION', 'OWNERSHIP', 'STEP_UP')
                AND operator_id IS NOT NULL
                AND session_family_id IS NOT NULL)
            OR (purpose = 'AUTHENTICATION'
                AND operator_id IS NULL
                AND session_family_id IS NULL));
