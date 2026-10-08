-- Delivery is separate from AgentRun completion. External effects use stable
-- server-created identities and an outbox committed before dispatch.
CREATE TABLE mobile_delivery_operation (
    id UUID PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES work_session(id) ON DELETE RESTRICT,
    operator_id BIGINT NOT NULL REFERENCES operator_account(id) ON DELETE RESTRICT,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('PUBLISH_PR', 'INTEGRATE', 'RELEASE')),
    target VARCHAR(24) NOT NULL CHECK (target IN ('APP_PROD', 'AX42_PLATFORM', 'ANDROID_STABLE')),
    source_commit VARCHAR(40) CHECK (source_commit IS NULL OR source_commit ~ '^[0-9a-f]{40}$'),
    execution_id UUID NOT NULL UNIQUE,
    state VARCHAR(24) NOT NULL CHECK (state IN (
        'QUEUED', 'WAITING_CI', 'PLANNING', 'PREPARING', 'READY', 'CONFIRMED',
        'ACCEPTED', 'APPLYING', 'ROLLING_BACK', 'QUARANTINED', 'SUCCEEDED', 'ROLLED_BACK',
        'FAILED', 'BLOCKED', 'ROLLBACK_FAILED')),
    plan_sha256 VARCHAR(64) CHECK (plan_sha256 IS NULL OR plan_sha256 ~ '^[0-9a-f]{64}$'),
    evidence_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CHECK (kind = 'RELEASE' OR target = 'APP_PROD'),
    CHECK (state <> 'CONFIRMED' OR (kind = 'RELEASE' AND plan_sha256 IS NOT NULL))
);
CREATE UNIQUE INDEX mobile_delivery_one_active
    ON mobile_delivery_operation(session_id, kind, target)
    WHERE state NOT IN ('SUCCEEDED', 'ROLLED_BACK', 'FAILED', 'BLOCKED', 'ROLLBACK_FAILED');
CREATE INDEX mobile_delivery_reconcile ON mobile_delivery_operation(updated_at)
    WHERE state NOT IN ('READY', 'SUCCEEDED', 'ROLLED_BACK', 'FAILED', 'BLOCKED', 'ROLLBACK_FAILED');
CREATE UNIQUE INDEX mobile_delivery_one_global_release ON mobile_delivery_operation(kind)
    WHERE kind = 'RELEASE' AND state NOT IN ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED');

COMMENT ON TABLE mobile_delivery_operation IS
    'Operator-authorized delivery outbox; never client commands, paths or credentials. Evidence retained after terminal completion.';

-- The release acceptance takes the same transaction lock and observes zero
-- active runs before committing CONFIRMED. Admission cannot race that check.
CREATE FUNCTION guard_agent_run_during_mobile_release() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status NOT IN ('SUCCEEDED','FAILED','CANCELLED') THEN
        PERFORM pg_advisory_xact_lock(814205002);
        IF EXISTS (SELECT 1 FROM mobile_delivery_operation WHERE kind='RELEASE'
            AND state IN ('CONFIRMED','ACCEPTED','APPLYING','ROLLING_BACK','QUARANTINED','ROLLBACK_FAILED')) THEN
            RAISE EXCEPTION 'ATENEA_RELEASE_IN_PROGRESS' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_run_mobile_release_admission
BEFORE INSERT OR UPDATE OF status ON agent_run FOR EACH ROW EXECUTE FUNCTION guard_agent_run_during_mobile_release();
