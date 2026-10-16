-- Server-owned recovery intents; historical validation results are never rewritten.
CREATE TABLE mobile_validation_recovery (
    id UUID PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES work_session(id) ON DELETE RESTRICT,
    operator_id BIGINT NOT NULL REFERENCES operator_account(id) ON DELETE RESTRICT,
    validation_id UUID NOT NULL REFERENCES validation_operation(id) ON DELETE RESTRICT,
    kind VARCHAR(16) NOT NULL CHECK(kind IN ('REPAIR','INVESTIGATION')),
    state VARCHAR(24) NOT NULL CHECK(state IN ('PREPARE_REQUESTED','PREPARING','READY',
        'APPLY_REQUESTED','APPLYING','APPLIED','ATTENTION','CANCELLED','INVESTIGATING','INVESTIGATED')),
    source_revision BIGINT NOT NULL CHECK(source_revision>=0),
    source_fingerprint_sha256 VARCHAR(64) NOT NULL CHECK(source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    command_json JSONB,
    evidence_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_fingerprint_sha256 VARCHAR(64) CHECK(result_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    run_id BIGINT REFERENCES agent_run(id) ON DELETE RESTRICT,
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(session_id,validation_id,kind),
    CHECK(kind<>'REPAIR' OR command_json IS NOT NULL)
);
CREATE INDEX mobile_validation_recovery_pending ON mobile_validation_recovery(updated_at)
    WHERE state IN ('PREPARE_REQUESTED','PREPARING','APPLY_REQUESTED','APPLYING');

CREATE FUNCTION guard_work_during_validation_repair() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status NOT IN ('SUCCEEDED','FAILED','CANCELLED','BLOCKED') THEN
        PERFORM pg_advisory_xact_lock(814205002);
        IF EXISTS(SELECT 1 FROM mobile_validation_recovery WHERE kind='REPAIR'
            AND state IN ('PREPARE_REQUESTED','PREPARING','APPLY_REQUESTED','APPLYING','ATTENTION')) THEN
            RAISE EXCEPTION 'ATENEA_VALIDATION_REPAIR_IN_PROGRESS' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_run_validation_repair_admission BEFORE INSERT OR UPDATE OF status ON agent_run
    FOR EACH ROW EXECUTE FUNCTION guard_work_during_validation_repair();
CREATE TRIGGER validation_repair_admission BEFORE INSERT OR UPDATE OF status ON validation_operation
    FOR EACH ROW EXECUTE FUNCTION guard_work_during_validation_repair();

CREATE FUNCTION guard_validation_repair_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(814205002);
    IF TG_OP='UPDATE' AND (NEW.id,NEW.session_id,NEW.operator_id,NEW.validation_id,NEW.kind,
        NEW.source_revision,NEW.source_fingerprint_sha256,NEW.command_json,NEW.created_at)
        IS DISTINCT FROM (OLD.id,OLD.session_id,OLD.operator_id,OLD.validation_id,OLD.kind,
        OLD.source_revision,OLD.source_fingerprint_sha256,OLD.command_json,OLD.created_at) THEN
        RAISE EXCEPTION 'ATENEA_VALIDATION_REPAIR_INTENT_IMMUTABLE' USING ERRCODE='55000';
    END IF;
    IF NEW.kind='REPAIR' AND NEW.state IN ('PREPARE_REQUESTED','PREPARING','APPLY_REQUESTED','APPLYING') THEN
        IF EXISTS(SELECT 1 FROM agent_run WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED'))
            OR EXISTS(SELECT 1 FROM validation_operation WHERE status='RUNNING')
            OR EXISTS(SELECT 1 FROM mobile_delivery_operation WHERE kind='RELEASE'
                AND state NOT IN ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED'))
            OR EXISTS(SELECT 1 FROM mobile_source_update_operation WHERE session_id=NEW.session_id
                AND state NOT IN ('PUBLISHED','FAILED','BLOCKED')) THEN
            RAISE EXCEPTION 'ATENEA_VALIDATION_REPAIR_EXECUTION_ACTIVE' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validation_repair_intent_guard BEFORE INSERT OR UPDATE ON mobile_validation_recovery
    FOR EACH ROW EXECUTE FUNCTION guard_validation_repair_intent();

CREATE FUNCTION guard_delivery_during_validation_repair() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state NOT IN ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED','PUBLISHED','CANCELLED') THEN
        PERFORM pg_advisory_xact_lock(814205002);
        IF EXISTS(SELECT 1 FROM mobile_validation_recovery WHERE kind='REPAIR'
            AND state IN ('PREPARE_REQUESTED','PREPARING','APPLY_REQUESTED','APPLYING','ATTENTION')) THEN
            RAISE EXCEPTION 'ATENEA_VALIDATION_REPAIR_IN_PROGRESS' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER delivery_validation_repair_admission BEFORE INSERT OR UPDATE OF state ON mobile_delivery_operation
    FOR EACH ROW EXECUTE FUNCTION guard_delivery_during_validation_repair();
CREATE TRIGGER source_update_validation_repair_admission BEFORE INSERT OR UPDATE OF state ON mobile_source_update_operation
    FOR EACH ROW EXECUTE FUNCTION guard_delivery_during_validation_repair();
