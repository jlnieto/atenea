-- Reauthorize one retained intent, never replace its main/head/key or its history.
CREATE TABLE mobile_source_recovery_authorization (
    preparation_id UUID NOT NULL REFERENCES mobile_source_update_operation(id),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('PREPARATION','FINALIZATION')),
    operator_id BIGINT NOT NULL REFERENCES operator_account(id),
    prior_state VARCHAR(24) NOT NULL,
    prior_delivery_state VARCHAR(24),
    prior_error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (preparation_id,kind)
);
CREATE FUNCTION guard_source_recovery_authorization() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(814205002);
    IF TG_OP='UPDATE' THEN
        RAISE EXCEPTION 'SOURCE_RECOVERY_IMMUTABLE_AUTHORIZATION' USING ERRCODE='55000';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM operator_account WHERE id=NEW.operator_id AND active
        AND codex_operations_role='PLATFORM_ADMINISTRATOR') OR NOT EXISTS (
        SELECT 1 FROM mobile_source_update_operation p WHERE p.id=NEW.preparation_id AND (
            (NEW.kind='PREPARATION' AND p.state=NEW.prior_state AND p.state IN ('ATTENTION','BLOCKED','UNCERTAIN')
                AND NEW.prior_delivery_state IS NULL AND NEW.prior_error_code IS NOT DISTINCT FROM p.error_code
                AND p.preparation_json IS NULL AND p.resolver_run_id IS NULL)
            OR (NEW.kind='FINALIZATION' AND EXISTS (SELECT 1 FROM mobile_source_finalization f
                JOIN mobile_delivery_operation d ON d.id=f.delivery_operation_id
                WHERE f.preparation_id=p.id AND f.state=NEW.prior_state AND f.state IN ('ATTENTION','UNCERTAIN')
                    AND d.state=NEW.prior_delivery_state AND d.error_code IS NOT DISTINCT FROM NEW.prior_error_code)))) THEN
        RAISE EXCEPTION 'SOURCE_RECOVERY_NOT_AUTHORIZED' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_recovery_authorization_immutable BEFORE INSERT OR UPDATE ON mobile_source_recovery_authorization
    FOR EACH ROW EXECUTE FUNCTION guard_source_recovery_authorization();
