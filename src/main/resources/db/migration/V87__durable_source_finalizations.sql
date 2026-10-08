-- A new publication receipt extends, never overwrites, the predecessor history.
CREATE TABLE mobile_source_finalization (
    id UUID PRIMARY KEY,
    preparation_id UUID NOT NULL UNIQUE REFERENCES mobile_source_update_operation(id),
    delivery_operation_id UUID NOT NULL REFERENCES mobile_delivery_operation(id),
    session_id BIGINT NOT NULL REFERENCES work_session(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED','CLAIMED','UNCERTAIN','ATTENTION','PUBLISHED')),
    command_json JSONB NOT NULL,
    predecessor_json JSONB NOT NULL,
    result_json JSONB,
    lease_until TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((state='PUBLISHED') = (result_json IS NOT NULL))
);
CREATE UNIQUE INDEX mobile_source_finalization_one_global ON mobile_source_finalization((true)) WHERE state <> 'PUBLISHED';
CREATE FUNCTION guard_source_finalization_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.preparation_id,NEW.delivery_operation_id,NEW.session_id,NEW.command_json,NEW.predecessor_json)
        IS DISTINCT FROM ROW(OLD.id,OLD.preparation_id,OLD.delivery_operation_id,OLD.session_id,OLD.command_json,OLD.predecessor_json)
        OR (OLD.result_json IS NOT NULL AND ROW(NEW.result_json,NEW.state) IS DISTINCT FROM ROW(OLD.result_json,OLD.state)) THEN
        RAISE EXCEPTION 'SOURCE_FINALIZATION_IMMUTABLE_EVIDENCE' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_finalization_immutable BEFORE UPDATE ON mobile_source_finalization
    FOR EACH ROW EXECUTE FUNCTION guard_source_finalization_evidence();

CREATE FUNCTION guard_execution_during_source_finalization() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(814205002);
    IF ((TG_TABLE_NAME='agent_run' AND NEW.status NOT IN ('SUCCEEDED','FAILED','CANCELLED'))
        OR (TG_TABLE_NAME='validation_operation' AND NEW.status='RUNNING'))
        AND EXISTS (SELECT 1 FROM mobile_source_finalization WHERE state <> 'PUBLISHED') THEN
        RAISE EXCEPTION 'SOURCE_FINALIZATION_IN_PROGRESS' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_run_source_finalization_admission BEFORE INSERT OR UPDATE OF status ON agent_run
    FOR EACH ROW EXECUTE FUNCTION guard_execution_during_source_finalization();
CREATE TRIGGER validation_source_finalization_admission BEFORE INSERT OR UPDATE OF status ON validation_operation
    FOR EACH ROW EXECUTE FUNCTION guard_execution_during_source_finalization();

CREATE FUNCTION guard_session_close_during_source_finalization() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.status IS DISTINCT FROM OLD.status OR NEW.remote_close_state IS DISTINCT FROM OLD.remote_close_state)
        AND EXISTS (SELECT 1 FROM mobile_source_finalization WHERE session_id=NEW.id AND state <> 'PUBLISHED') THEN
        RAISE EXCEPTION 'SOURCE_FINALIZATION_IN_PROGRESS' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER work_session_source_finalization_close BEFORE UPDATE OF status,remote_close_state ON work_session
    FOR EACH ROW EXECUTE FUNCTION guard_session_close_during_source_finalization();

CREATE FUNCTION guard_workspace_during_source_finalization() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state IN ('REQUESTED','DISPATCHED') AND EXISTS (
        SELECT 1 FROM mobile_source_finalization op JOIN work_session ws ON ws.id=op.session_id
        WHERE ws.development_change_id=NEW.development_change_id AND op.state <> 'PUBLISHED') THEN
        RAISE EXCEPTION 'SOURCE_FINALIZATION_IN_PROGRESS' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER workspace_source_finalization_admission BEFORE INSERT OR UPDATE OF state ON development_change_workspace_operation
    FOR EACH ROW EXECUTE FUNCTION guard_workspace_during_source_finalization();

ALTER TABLE mobile_source_update_operation DROP CONSTRAINT mobile_source_update_operation_state_check;
ALTER TABLE mobile_source_update_operation ADD CHECK (state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION',
    'READY_TO_RESOLVE','RESOLVING','RESOLVER_COMPLETED','READY_TO_FINALIZE','FAILED','BLOCKED','PUBLISHED'));
