-- Finishing a delivered change is not another publication or AgentRun.
ALTER TABLE mobile_delivery_operation DROP CONSTRAINT mobile_delivery_operation_kind_check;
ALTER TABLE mobile_delivery_operation ADD CONSTRAINT mobile_delivery_operation_kind_check
    CHECK (kind IN ('PUBLISH_PR','INTEGRATE','RELEASE','FINALIZE'));
CREATE UNIQUE INDEX mobile_delivery_one_completion_per_session
    ON mobile_delivery_operation(session_id) WHERE kind='FINALIZE';
CREATE UNIQUE INDEX mobile_delivery_one_completion_per_change
    ON mobile_delivery_operation((evidence_json->'completion'->>'changeKey')) WHERE kind='FINALIZE';

-- Use the existing admission lock, retaining the global release safeguard.
CREATE OR REPLACE FUNCTION guard_agent_run_during_mobile_release() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status NOT IN ('SUCCEEDED','FAILED','CANCELLED') THEN
        PERFORM pg_advisory_xact_lock(814205002);
        IF EXISTS (SELECT 1 FROM mobile_delivery_operation WHERE kind='RELEASE'
            AND state IN ('CONFIRMED','ACCEPTED','APPLYING','ROLLING_BACK','QUARANTINED','ROLLBACK_FAILED')) THEN
            RAISE EXCEPTION 'ATENEA_RELEASE_IN_PROGRESS' USING ERRCODE='55000';
        END IF;
        IF EXISTS (SELECT 1 FROM mobile_delivery_operation
            WHERE kind='FINALIZE' AND session_id=NEW.session_id) THEN
            RAISE EXCEPTION 'ATENEA_CHANGE_COMPLETION_IN_PROGRESS' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_validation_during_change_completion() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='RUNNING' THEN
        PERFORM pg_advisory_xact_lock(814205002);
        IF EXISTS (SELECT 1 FROM mobile_delivery_operation
            WHERE kind='FINALIZE' AND session_id=NEW.work_session_id) THEN
            RAISE EXCEPTION 'ATENEA_CHANGE_COMPLETION_IN_PROGRESS' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validation_change_completion_admission
BEFORE INSERT OR UPDATE OF status ON validation_operation
FOR EACH ROW EXECUTE FUNCTION guard_validation_during_change_completion();
