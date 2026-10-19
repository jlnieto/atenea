-- A correction remains owned by its original Change/WorkSession and sealed integration.
CREATE TABLE mobile_release_build_repair (
    id uuid PRIMARY KEY,
    session_id bigint NOT NULL REFERENCES work_session(id),
    operator_id bigint NOT NULL REFERENCES operator_account(id),
    failed_delivery_id uuid NOT NULL UNIQUE REFERENCES mobile_delivery_operation(id),
    preparation_id uuid NOT NULL UNIQUE REFERENCES mobile_source_update_operation(id),
    source_commit varchar(40) NOT NULL CHECK (source_commit ~ '^[0-9a-f]{40}$'),
    original_pr_url text NOT NULL,
    original_head varchar(40) NOT NULL,
    original_merge varchar(40) NOT NULL,
    diagnostic_json jsonb NOT NULL,
    required_targets_json jsonb NOT NULL CHECK (jsonb_typeof(required_targets_json)='array'
        AND required_targets_json <@ '["APP_PROD","ANDROID_STABLE"]'::jsonb),
    prepared_fingerprint varchar(64) CHECK(prepared_fingerprint ~ '^[0-9a-f]{64}$'),
    repair_head varchar(40),
    repair_pr_url text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION protect_release_build_repair() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (to_jsonb(NEW)-'repair_head'-'repair_pr_url'-'prepared_fingerprint') IS DISTINCT FROM
            (to_jsonb(OLD)-'repair_head'-'repair_pr_url'-'prepared_fingerprint')
       OR (OLD.prepared_fingerprint IS NOT NULL AND NEW.prepared_fingerprint IS DISTINCT FROM OLD.prepared_fingerprint)
       OR (OLD.repair_head IS NOT NULL AND NEW.repair_head IS DISTINCT FROM OLD.repair_head)
       OR (OLD.repair_pr_url IS NOT NULL AND NEW.repair_pr_url IS DISTINCT FROM OLD.repair_pr_url)
       OR (NEW.repair_head IS NOT NULL AND NEW.repair_head !~ '^[0-9a-f]{40}$')
       OR (NEW.repair_pr_url IS NOT NULL AND NEW.repair_pr_url !~ '^https://github.com/jlnieto/atenea/pull/[1-9][0-9]*$')
    THEN RAISE EXCEPTION 'RELEASE_REPAIR_IMMUTABLE_EVIDENCE'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER release_build_repair_immutable BEFORE UPDATE OR DELETE ON mobile_release_build_repair
    FOR EACH ROW EXECUTE FUNCTION protect_release_build_repair();

CREATE FUNCTION authorize_release_build_repair() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(814205002);
    IF NOT COALESCE(EXISTS (
        SELECT 1 FROM work_session s JOIN development_change c ON c.id=s.development_change_id
        JOIN mobile_source_update_operation p ON p.id=NEW.preparation_id
        JOIN mobile_delivery_operation failed ON failed.id=NEW.failed_delivery_id
        JOIN operator_account a ON a.id=NEW.operator_id
        WHERE s.id=NEW.session_id AND s.status='OPEN' AND s.execution_target='REMOTE'
          AND s.pull_request_status='MERGED' AND s.acceptance_state='INTEGRATION_READY'
          AND c.status='OPEN' AND c.workspace_state='READY' AND c.validation_state='CURRENT'
          AND a.active AND a.codex_operations_role='PLATFORM_ADMINISTRATOR'
          AND p.session_id=s.id AND p.operator_id=a.id AND p.state='QUEUED'
          AND p.command_json->>'targetMainCommit'=NEW.source_commit
          AND p.command_json->'owner'->>'changeKey'=c.change_key::text
          AND p.command_json->'owner'->>'sourceCommit'=s.final_commit_sha
          AND p.command_json->'owner'->>'workspaceIdentity'=s.workspace_identity
          AND p.publication_receipt_sha256=s.publication_receipt_sha256
          AND failed.session_id=s.id AND failed.operator_id=a.id AND failed.kind='RELEASE'
          AND failed.state='BLOCKED' AND failed.error_code='RELEASE_BUILD_FAILED'
          AND failed.source_commit=NEW.source_commit
          AND s.pull_request_url=NEW.original_pr_url AND s.final_commit_sha=NEW.original_head
          AND NEW.diagnostic_json->>'sourceCommit'=NEW.source_commit
          AND NEW.diagnostic_json->>'state'='FAILED'
          AND (NEW.diagnostic_json->>'runId')::bigint>0
          AND (NEW.diagnostic_json->>'attempt')::integer>0
          AND EXISTS (SELECT 1 FROM mobile_delivery_operation i WHERE i.session_id=s.id
            AND i.kind='INTEGRATE' AND i.target='APP_PROD' AND i.state='SUCCEEDED'
            AND i.source_commit=NEW.original_head AND i.evidence_json->>'mergeCommit'=NEW.original_merge)
    ),false) THEN RAISE EXCEPTION 'RELEASE_REPAIR_AUTHORITY_REQUIRED'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER release_build_repair_authority BEFORE INSERT ON mobile_release_build_repair
    FOR EACH ROW EXECUTE FUNCTION authorize_release_build_repair();

-- Preserve every v89 owner/retry restriction. The only additional admission is
-- the observed clean fingerprint of this sealed post-integration repair.
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
                        AND (NEW.change_source_fingerprint_sha256=op.preparation_json->>'preparedFingerprintSha256'
                          OR (op.preparation_json->>'preparedFingerprintSha256' IS NULL AND EXISTS (
                            SELECT 1 FROM mobile_release_build_repair b WHERE b.preparation_id=op.id
                              AND b.session_id=op.session_id AND b.prepared_fingerprint=NEW.change_source_fingerprint_sha256)))
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
