-- A retry extends the audit trail. Never replace the original resolver/run/receipt.
ALTER TABLE mobile_source_update_operation DROP CONSTRAINT mobile_source_update_operation_state_check;
ALTER TABLE mobile_source_update_operation ADD CONSTRAINT mobile_source_update_operation_state_check CHECK
    (state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE','RETRY_REQUESTED',
              'RESOLVING','RESOLVER_COMPLETED','READY_TO_FINALIZE','FAILED','BLOCKED','PUBLISHED'));

CREATE TABLE mobile_source_resolver_retry (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES mobile_source_update_operation(id),
    source_run_id BIGINT NOT NULL UNIQUE REFERENCES agent_run(id),
    operator_id BIGINT NOT NULL REFERENCES operator_account(id),
    run_id BIGINT UNIQUE REFERENCES agent_run(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION guard_source_resolver_retry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op mobile_source_update_operation; source agent_run;
BEGIN
    PERFORM pg_advisory_xact_lock(814205002);
    IF TG_OP='UPDATE' THEN
        IF ROW(NEW.id,NEW.operation_id,NEW.source_run_id,NEW.operator_id,NEW.created_at)
           IS DISTINCT FROM ROW(OLD.id,OLD.operation_id,OLD.source_run_id,OLD.operator_id,OLD.created_at)
           OR (OLD.run_id IS NOT NULL AND NEW.run_id IS DISTINCT FROM OLD.run_id) THEN
            RAISE EXCEPTION 'SOURCE_UPDATE_RETRY_IMMUTABLE' USING ERRCODE='55000';
        END IF;
        IF NEW.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM agent_run r
            WHERE r.id=NEW.run_id AND r.retry_of_run_id=NEW.source_run_id) THEN
            RAISE EXCEPTION 'SOURCE_UPDATE_RETRY_FOREIGN_RUN' USING ERRCODE='55000';
        END IF;
    ELSE
        SELECT * INTO op FROM mobile_source_update_operation WHERE id=NEW.operation_id;
        SELECT * INTO source FROM agent_run WHERE id=NEW.source_run_id;
        IF NOT COALESCE(op.state='FAILED' AND source.status='FAILED' AND source.session_id=op.session_id
            AND source.origin_turn_id=op.resolver_turn_id AND source.change_source_revision=op.prepared_revision
            AND source.id=COALESCE((SELECT run_id FROM mobile_source_resolver_retry WHERE operation_id=op.id
                AND run_id IS NOT NULL ORDER BY created_at DESC,id DESC LIMIT 1),op.resolver_run_id)
            AND EXISTS (SELECT 1 FROM operator_account WHERE id=NEW.operator_id AND active
                AND codex_operations_role='PLATFORM_ADMINISTRATOR'),false) OR NEW.run_id IS NOT NULL THEN
            RAISE EXCEPTION 'SOURCE_UPDATE_RETRY_NOT_AUTHORIZED' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_resolver_retry_authority BEFORE INSERT OR UPDATE ON mobile_source_resolver_retry
    FOR EACH ROW EXECUTE FUNCTION guard_source_resolver_retry();

CREATE OR REPLACE FUNCTION guard_agent_run_during_source_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status NOT IN ('SUCCEEDED','FAILED','CANCELLED') THEN
        PERFORM pg_advisory_xact_lock(814205002);
        IF EXISTS (SELECT 1 FROM mobile_source_update_operation op
            WHERE op.state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE','RETRY_REQUESTED','RESOLVING')
              AND NOT COALESCE((op.session_id=NEW.session_id AND op.resolver_turn_id=NEW.origin_turn_id
                  AND NEW.execution_target='REMOTE' AND NEW.workload_kind='project-codex-v4'
                  AND NEW.development_change_key::text=op.command_json->'owner'->>'changeKey'
                  AND NEW.change_source_revision=op.prepared_revision
                  AND NEW.change_base_commit=op.command_json->'owner'->>'baseCommit'
                  AND NEW.repository_commit=op.command_json->'owner'->>'sourceCommit'
                  AND NEW.change_expected_canonical_commit=NEW.repository_commit
                  AND NEW.selected_worker_id=op.command_json->'owner'->>'workerId'
                  AND NEW.workspace_identity=op.command_json->'owner'->>'workspaceIdentity'
                  AND NEW.change_source_fingerprint_sha256=op.preparation_json->>'preparedFingerprintSha256'
                  AND NEW.change_workspace_ownership_fingerprint_sha256=NEW.change_source_fingerprint_sha256
                  AND ((op.state='READY_TO_RESOLVE' AND op.resolver_run_id IS NULL)
                    OR (op.resolver_run_id=NEW.id AND NOT EXISTS (SELECT 1 FROM mobile_source_resolver_retry WHERE operation_id=op.id))
                    OR EXISTS (SELECT 1 FROM mobile_source_resolver_retry r WHERE r.operation_id=op.id
                        AND ((r.run_id=NEW.id) OR (op.state='RETRY_REQUESTED' AND r.run_id IS NULL AND NEW.retry_of_run_id=r.source_run_id))))),false)) THEN
            RAISE EXCEPTION 'SOURCE_UPDATE_IN_PROGRESS' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- Extend the existing barriers, retaining the original functions and their checks.
CREATE FUNCTION guard_pending_resolver_retry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE blocked boolean;
BEGIN
    PERFORM pg_advisory_xact_lock(814205002);
    IF TG_TABLE_NAME='validation_operation' THEN
        blocked:=NEW.status='RUNNING' AND EXISTS (SELECT 1 FROM mobile_source_update_operation WHERE state='RETRY_REQUESTED');
    ELSIF TG_TABLE_NAME='work_session' THEN
        blocked:=(NEW.status IS DISTINCT FROM OLD.status OR NEW.remote_close_state IS DISTINCT FROM OLD.remote_close_state)
            AND EXISTS (SELECT 1 FROM mobile_source_update_operation WHERE session_id=NEW.id AND state='RETRY_REQUESTED');
    ELSE
        blocked:=NEW.state IN ('REQUESTED','DISPATCHED') AND EXISTS (
            SELECT 1 FROM mobile_source_update_operation op JOIN work_session ws ON ws.id=op.session_id
            WHERE ws.development_change_id=NEW.development_change_id AND op.state='RETRY_REQUESTED');
    END IF;
    IF blocked THEN RAISE EXCEPTION 'SOURCE_UPDATE_RETRY_IN_PROGRESS' USING ERRCODE='55000'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validation_resolver_retry_admission BEFORE INSERT OR UPDATE OF status ON validation_operation
    FOR EACH ROW EXECUTE FUNCTION guard_pending_resolver_retry();
CREATE TRIGGER work_session_resolver_retry_close BEFORE UPDATE OF status,remote_close_state ON work_session
    FOR EACH ROW EXECUTE FUNCTION guard_pending_resolver_retry();
CREATE TRIGGER workspace_resolver_retry_admission BEFORE INSERT OR UPDATE OF state ON development_change_workspace_operation
    FOR EACH ROW EXECUTE FUNCTION guard_pending_resolver_retry();

CREATE UNIQUE INDEX source_update_one_global_retry ON mobile_source_update_operation((true))
    WHERE state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE','RETRY_REQUESTED','RESOLVING');
