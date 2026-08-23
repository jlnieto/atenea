ALTER TABLE operator_account
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN role_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_operator_credential_version
        CHECK (credential_version >= 0),
    ADD CONSTRAINT ck_operator_role_version
        CHECK (role_version >= 0);

CREATE TABLE operator_session_family (
    id UUID PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    client_type VARCHAR(24) NOT NULL,
    device_label VARCHAR(120) NOT NULL,
    current_generation BIGINT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(40),
    CONSTRAINT fk_operator_session_family_operator
        FOREIGN KEY (operator_id) REFERENCES operator_account (id) ON DELETE CASCADE,
    CONSTRAINT ck_operator_session_family_client_type
        CHECK (client_type ~ '^[A-Z][A-Z0-9_]{1,23}$'),
    CONSTRAINT ck_operator_session_family_device_label
        CHECK (length(device_label) BETWEEN 1 AND 120
            AND device_label = btrim(device_label)
            AND device_label !~ '[[:cntrl:]]'),
    CONSTRAINT ck_operator_session_family_generation
        CHECK (current_generation >= 0),
    CONSTRAINT ck_operator_session_family_row_version
        CHECK (row_version >= 0),
    CONSTRAINT ck_operator_session_family_timestamps
        CHECK (last_used_at >= created_at
            AND last_used_at <= absolute_expires_at
            AND absolute_expires_at > created_at),
    CONSTRAINT ck_operator_session_family_revocation
        CHECK (
            (revoked_at IS NULL AND revocation_reason IS NULL)
            OR (revoked_at IS NOT NULL
                AND revoked_at >= created_at
                AND revocation_reason IS NOT NULL
                AND revocation_reason ~ '^[A-Z][A-Z0-9_]{2,39}$')
        )
);

CREATE INDEX idx_operator_session_family_active_inventory
    ON operator_session_family (operator_id, last_used_at DESC, id)
    WHERE revoked_at IS NULL;

ALTER TABLE operator_refresh_token
    ADD COLUMN session_family_id UUID,
    ADD COLUMN generation BIGINT,
    ADD COLUMN consumed_at TIMESTAMPTZ,
    ADD COLUMN replaced_by_token_id BIGINT,
    ADD COLUMN revocation_reason VARCHAR(40),
    ADD CONSTRAINT fk_operator_refresh_token_session_family
        FOREIGN KEY (session_family_id)
        REFERENCES operator_session_family (id) ON DELETE RESTRICT,
    ADD CONSTRAINT uk_operator_refresh_token_id_family
        UNIQUE (id, session_family_id),
    ADD CONSTRAINT uk_operator_refresh_token_family_generation
        UNIQUE (session_family_id, generation),
    ADD CONSTRAINT fk_operator_refresh_token_replacement_family
        FOREIGN KEY (replaced_by_token_id, session_family_id)
        REFERENCES operator_refresh_token (id, session_family_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_operator_refresh_token_family_generation
        CHECK (
            (session_family_id IS NULL AND generation IS NULL)
            OR (session_family_id IS NOT NULL AND generation >= 0)
        ),
    ADD CONSTRAINT ck_operator_refresh_token_consumed
        CHECK (consumed_at IS NULL
            OR (consumed_at >= created_at AND revoked_at IS NOT NULL)),
    ADD CONSTRAINT ck_operator_refresh_token_replacement
        CHECK (replaced_by_token_id IS NULL
            OR (replaced_by_token_id <> id
                AND session_family_id IS NOT NULL
                AND consumed_at IS NOT NULL)),
    ADD CONSTRAINT ck_operator_refresh_token_revocation_reason
        CHECK (revocation_reason IS NULL
            OR (revoked_at IS NOT NULL
                AND revocation_reason ~ '^[A-Z][A-Z0-9_]{2,39}$'));

CREATE INDEX idx_operator_refresh_token_active_family
    ON operator_refresh_token (session_family_id, generation DESC)
    WHERE session_family_id IS NOT NULL AND revoked_at IS NULL;
