-- Bind each authorized retry to its observed source, keeping original evidence immutable.
ALTER TABLE mobile_source_resolver_retry ADD COLUMN source_revision BIGINT;
ALTER TABLE mobile_source_resolver_retry ADD COLUMN observed_fingerprint_sha256 VARCHAR(64);
ALTER TABLE mobile_source_resolver_retry ADD COLUMN workspace_dirty BOOLEAN;
ALTER TABLE mobile_source_resolver_retry ADD COLUMN observation_json JSONB;

-- V88 admitted only the exact prepared dirty source. Keep its known binding;
-- absence of a historical HTTP observation remains explicit, never fabricated.
UPDATE mobile_source_resolver_retry r SET source_revision=a.change_source_revision,
    observed_fingerprint_sha256=a.change_source_fingerprint_sha256,workspace_dirty=true
    FROM agent_run a WHERE a.id=r.source_run_id;
ALTER TABLE mobile_source_resolver_retry ALTER COLUMN source_revision SET NOT NULL;
ALTER TABLE mobile_source_resolver_retry ALTER COLUMN observed_fingerprint_sha256 SET NOT NULL;
ALTER TABLE mobile_source_resolver_retry ALTER COLUMN workspace_dirty SET NOT NULL;
ALTER TABLE mobile_source_resolver_retry ADD CONSTRAINT resolver_retry_observed_source CHECK
    (source_revision>=1 AND observed_fingerprint_sha256 ~ '^[0-9a-f]{64}$');

CREATE OR REPLACE FUNCTION guard_source_resolver_retry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE op mobile_source_update_operation; source agent_run; ws work_session; dc development_change;
BEGIN
    PERFORM pg_advisory_xact_lock(814205002);
    IF TG_OP='UPDATE' THEN
        IF ROW(NEW.id,NEW.operation_id,NEW.source_run_id,NEW.operator_id,NEW.created_at,
               NEW.source_revision,NEW.observed_fingerprint_sha256,NEW.workspace_dirty,NEW.observation_json)
           IS DISTINCT FROM ROW(OLD.id,OLD.operation_id,OLD.source_run_id,OLD.operator_id,OLD.created_at,
               OLD.source_revision,OLD.observed_fingerprint_sha256,OLD.workspace_dirty,OLD.observation_json)
           OR (OLD.run_id IS NOT NULL AND NEW.run_id IS DISTINCT FROM OLD.run_id) THEN
            RAISE EXCEPTION 'SOURCE_UPDATE_RETRY_IMMUTABLE' USING ERRCODE='55000';
        END IF;
        IF NEW.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM agent_run r JOIN agent_run predecessor ON predecessor.id=NEW.source_run_id
            WHERE r.id=NEW.run_id AND r.retry_of_run_id=NEW.source_run_id
              AND r.session_id=predecessor.session_id AND r.origin_turn_id=predecessor.origin_turn_id
              AND r.execution_target='REMOTE' AND r.workload_kind='project-codex-v4'
              AND r.development_change_key=predecessor.development_change_key AND r.change_base_commit=predecessor.change_base_commit
              AND r.repository_commit=predecessor.repository_commit AND r.selected_worker_id=predecessor.selected_worker_id
              AND r.workspace_identity=predecessor.workspace_identity AND r.change_source_revision=NEW.source_revision
              AND r.change_source_fingerprint_sha256=NEW.observed_fingerprint_sha256) THEN
            RAISE EXCEPTION 'SOURCE_UPDATE_RETRY_FOREIGN_RUN' USING ERRCODE='55000';
        END IF;
    ELSE
        SELECT * INTO op FROM mobile_source_update_operation WHERE id=NEW.operation_id;
        SELECT * INTO source FROM agent_run WHERE id=NEW.source_run_id;
        SELECT * INTO ws FROM work_session WHERE id=op.session_id;
        SELECT * INTO dc FROM development_change WHERE id=ws.development_change_id;
        IF NOT COALESCE(op.state='FAILED' AND source.status='FAILED' AND source.session_id=op.session_id
            AND source.origin_turn_id=op.resolver_turn_id AND source.execution_target='REMOTE' AND source.workload_kind='project-codex-v4'
            AND source.change_base_commit=op.command_json->'owner'->>'baseCommit'
            AND source.repository_commit=op.command_json->'owner'->>'sourceCommit'
            AND source.development_change_key::text=op.command_json->'owner'->>'changeKey'
            AND source.selected_worker_id=op.command_json->'owner'->>'workerId'
            AND source.workspace_identity=op.command_json->'owner'->>'workspaceIdentity'
            AND NEW.source_revision>=source.change_source_revision AND NEW.source_revision=dc.source_revision
            AND NEW.observed_fingerprint_sha256=dc.source_fingerprint_sha256
            AND dc.status='OPEN' AND ws.status='OPEN' AND dc.workspace_state='READY'
            AND dc.source_state=CASE WHEN NEW.workspace_dirty THEN 'DIRTY' ELSE 'CLEAN' END
            AND NEW.observation_json IS NOT NULL AND NEW.observation_json->>'state'='observed'
            AND NEW.observation_json->'valuesExposed'='false'::jsonb
            AND NEW.observation_json->>'projectId'='atenea'
            AND NEW.observation_json->>'sessionId'=ws.remote_session_id::text
            AND NEW.observation_json->>'workspaceIdentity'=ws.workspace_identity
            AND NEW.observation_json->>'headCommit'=source.repository_commit
            AND NEW.observation_json->>'fingerprintSha256'=NEW.observed_fingerprint_sha256
            AND (NEW.observation_json->>'stagedChangeCount')::bigint>=0
            AND (NEW.observation_json->>'unstagedChangeCount')::bigint>=0
            AND (NEW.observation_json->>'untrackedChangeCount')::bigint>=0
            AND NEW.workspace_dirty=((NEW.observation_json->>'stagedChangeCount')::bigint>0
                OR (NEW.observation_json->>'unstagedChangeCount')::bigint>0 OR (NEW.observation_json->>'untrackedChangeCount')::bigint>0)
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

CREATE OR REPLACE FUNCTION guard_agent_run_during_source_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status NOT IN ('SUCCEEDED','FAILED','CANCELLED') THEN
        PERFORM pg_advisory_xact_lock(814205002);
        IF EXISTS (SELECT 1 FROM mobile_source_update_operation op
            WHERE op.state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE','RETRY_REQUESTED','RESOLVING')
              AND NOT COALESCE((op.session_id=NEW.session_id AND op.resolver_turn_id=NEW.origin_turn_id
                  AND NEW.execution_target='REMOTE' AND NEW.workload_kind='project-codex-v4'
                  AND NEW.development_change_key::text=op.command_json->'owner'->>'changeKey'
                  AND NEW.change_base_commit=op.command_json->'owner'->>'baseCommit'
                  AND NEW.repository_commit=op.command_json->'owner'->>'sourceCommit'
                  AND NEW.change_expected_canonical_commit=NEW.repository_commit
                  AND NEW.selected_worker_id=op.command_json->'owner'->>'workerId'
                  AND NEW.workspace_identity=op.command_json->'owner'->>'workspaceIdentity'
                  AND NEW.change_workspace_ownership_fingerprint_sha256 IS NOT DISTINCT FROM NEW.change_source_fingerprint_sha256
                  AND (((op.state='READY_TO_RESOLVE' AND op.resolver_run_id IS NULL)
                        OR (op.resolver_run_id=NEW.id AND NOT EXISTS (SELECT 1 FROM mobile_source_resolver_retry WHERE operation_id=op.id)))
                        AND NEW.change_source_revision=op.prepared_revision
                        AND NEW.change_source_fingerprint_sha256=op.preparation_json->>'preparedFingerprintSha256'
                    OR EXISTS (SELECT 1 FROM mobile_source_resolver_retry r WHERE r.operation_id=op.id
                        AND ((r.run_id=NEW.id) OR (op.state='RETRY_REQUESTED' AND r.run_id IS NULL AND NEW.retry_of_run_id=r.source_run_id))
                        AND NEW.change_source_revision=r.source_revision
                        AND NEW.change_source_fingerprint_sha256=r.observed_fingerprint_sha256))),false)) THEN
            RAISE EXCEPTION 'SOURCE_UPDATE_IN_PROGRESS' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
