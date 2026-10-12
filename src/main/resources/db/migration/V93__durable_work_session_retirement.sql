-- Retirement preserves source/history and releases ownership; it is not delivery.
CREATE TABLE work_session_retirement (
    operation_id UUID PRIMARY KEY,
    session_id BIGINT NOT NULL UNIQUE REFERENCES work_session(id),
    operator_id BIGINT NOT NULL REFERENCES operator_account(id),
    idempotency_key UUID NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('APPLYING','BLOCKED','SUCCEEDED')),
    owner_json JSONB NOT NULL,
    evidence_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    UNIQUE (operator_id,idempotency_key)
);

CREATE FUNCTION guard_retirement_journal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.operation_id IS DISTINCT FROM OLD.operation_id OR NEW.session_id IS DISTINCT FROM OLD.session_id
        OR NEW.operator_id IS DISTINCT FROM OLD.operator_id OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.owner_json IS DISTINCT FROM OLD.owner_json OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'ATENEA_RETIREMENT_IDENTITY_IMMUTABLE' USING ERRCODE='23514';
    END IF;
    IF OLD.state='SUCCEEDED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'ATENEA_RETIREMENT_RESULT_IMMUTABLE' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER retirement_journal_guard BEFORE UPDATE ON work_session_retirement
    FOR EACH ROW EXECUTE FUNCTION guard_retirement_journal();

-- A durable retirement intent is an admission barrier, including after failure.
CREATE FUNCTION guard_work_during_session_retirement() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE sid BIGINT;
BEGIN
    IF TG_TABLE_NAME='agent_run' THEN
        IF NEW.status IN ('SUCCEEDED','FAILED','CANCELLED') THEN RETURN NEW; END IF;
        sid := NEW.session_id;
    ELSE
        IF NEW.status<>'RUNNING' THEN RETURN NEW; END IF;
        sid := NEW.work_session_id;
    END IF;
    PERFORM pg_advisory_xact_lock(814205002);
    IF EXISTS (SELECT 1 FROM work_session_retirement WHERE session_id=sid) THEN
        RAISE EXCEPTION 'ATENEA_SESSION_RETIREMENT_IN_PROGRESS' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_run_retirement_admission BEFORE INSERT OR UPDATE OF status ON agent_run
    FOR EACH ROW EXECUTE FUNCTION guard_work_during_session_retirement();
CREATE TRIGGER validation_retirement_admission BEFORE INSERT OR UPDATE OF status ON validation_operation
    FOR EACH ROW EXECUTE FUNCTION guard_work_during_session_retirement();

CREATE FUNCTION guard_rebinding_retired_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.development_change_id IS NOT NULL AND NEW.status IN ('OPEN','CLOSING') THEN
        PERFORM pg_advisory_xact_lock(814205002);
        IF EXISTS (SELECT 1 FROM work_session_retirement r JOIN development_change dc
            ON dc.change_key::text=r.owner_json->>'changeKey'
            WHERE dc.id=NEW.development_change_id AND r.session_id<>NEW.id) THEN
            RAISE EXCEPTION 'ATENEA_CHANGE_RETIREMENT_IN_PROGRESS' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER session_retirement_binding_guard BEFORE INSERT OR UPDATE OF status,development_change_id ON work_session
    FOR EACH ROW EXECUTE FUNCTION guard_rebinding_retired_change();
