ALTER TABLE operator_webauthn_challenge
    DROP CONSTRAINT ck_operator_webauthn_challenge_action,
    ADD CONSTRAINT ck_operator_webauthn_challenge_action
        CHECK ((purpose = 'STEP_UP'
                AND action_kind IS NOT NULL
                AND target_fingerprint IS NOT NULL
                AND plan_fingerprint IS NOT NULL
                AND action_kind ~ '^[A-Z][A-Z0-9_]{2,63}$'
                AND octet_length(target_fingerprint) = 32
                AND octet_length(plan_fingerprint) = 32)
            OR (purpose = 'OWNERSHIP'
                AND ((action_kind IS NULL
                        AND target_fingerprint IS NULL
                        AND plan_fingerprint IS NULL)
                    OR (action_kind = 'WEBAUTHN_CREDENTIAL_OWNERSHIP'
                        AND target_fingerprint IS NOT NULL
                        AND plan_fingerprint IS NOT NULL
                        AND octet_length(target_fingerprint) = 32
                        AND octet_length(plan_fingerprint) = 32)))
            OR (purpose NOT IN ('STEP_UP', 'OWNERSHIP')
                AND action_kind IS NULL
                AND target_fingerprint IS NULL
                AND plan_fingerprint IS NULL));
