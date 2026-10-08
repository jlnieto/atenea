-- Pinned conflict preparation is not validation, publication, or release.
CREATE TABLE mobile_source_update_operation (
    id UUID PRIMARY KEY,
    idempotency_key UUID NOT NULL UNIQUE,
    session_id BIGINT NOT NULL REFERENCES work_session(id),
    operator_id BIGINT NOT NULL REFERENCES operator_account(id),
    publication_receipt_sha256 VARCHAR(64) NOT NULL CHECK (publication_receipt_sha256 ~ '^[0-9a-f]{64}$'),
    original_source_commit VARCHAR(40) NOT NULL CHECK (original_source_commit ~ '^[0-9a-f]{40}$'),
    original_fingerprint_sha256 VARCHAR(64) NOT NULL CHECK (original_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    state VARCHAR(24) NOT NULL CHECK (state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN',
        'ATTENTION','READY_TO_RESOLVE','RESOLVING','RESOLVER_COMPLETED','READY_TO_FINALIZE','FAILED','BLOCKED')),
    command_json JSONB NOT NULL,
    preparation_json JSONB,
    prepared_revision BIGINT,
    resolver_turn_id BIGINT UNIQUE REFERENCES session_turn(id),
    resolver_run_id BIGINT UNIQUE REFERENCES agent_run(id),
    result_revision BIGINT,
    result_fingerprint_sha256 VARCHAR(64) CHECK (result_fingerprint_sha256 IS NULL OR result_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    error_code VARCHAR(80),
    lease_until TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(session_id, publication_receipt_sha256),
    CHECK ((resolver_run_id IS NULL) OR (resolver_turn_id IS NOT NULL AND prepared_revision IS NOT NULL)),
    CHECK (prepared_revision IS NULL OR prepared_revision >= 1),
    CHECK (result_revision IS NULL OR (prepared_revision IS NOT NULL AND result_revision > prepared_revision
        AND result_fingerprint_sha256 IS NOT NULL))
);
CREATE UNIQUE INDEX mobile_source_update_one_global_preparation ON mobile_source_update_operation((true))
    WHERE state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE');
COMMENT ON TABLE mobile_source_update_operation IS
    'Server-owned pinned intent, worker receipt and one resolver in the original WorkSession; previous validation history retained.';

CREATE FUNCTION guard_source_update_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.idempotency_key, NEW.session_id, NEW.operator_id,
           NEW.publication_receipt_sha256, NEW.command_json, NEW.original_source_commit, NEW.original_fingerprint_sha256)
       IS DISTINCT FROM ROW(OLD.id, OLD.idempotency_key, OLD.session_id, OLD.operator_id,
           OLD.publication_receipt_sha256, OLD.command_json, OLD.original_source_commit, OLD.original_fingerprint_sha256)
       OR (OLD.preparation_json IS NOT NULL AND NEW.preparation_json IS DISTINCT FROM OLD.preparation_json)
       OR (OLD.prepared_revision IS NOT NULL AND NEW.prepared_revision IS DISTINCT FROM OLD.prepared_revision)
       OR (OLD.resolver_turn_id IS NOT NULL AND NEW.resolver_turn_id IS DISTINCT FROM OLD.resolver_turn_id)
       OR (OLD.resolver_run_id IS NOT NULL AND NEW.resolver_run_id IS DISTINCT FROM OLD.resolver_run_id)
       OR (OLD.result_revision IS NOT NULL AND ROW(NEW.result_revision, NEW.result_fingerprint_sha256)
           IS DISTINCT FROM ROW(OLD.result_revision, OLD.result_fingerprint_sha256)) THEN
        RAISE EXCEPTION 'SOURCE_UPDATE_IMMUTABLE_EVIDENCE' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_update_immutable_intent BEFORE UPDATE ON mobile_source_update_operation
    FOR EACH ROW EXECUTE FUNCTION guard_source_update_intent();

-- Share admission serialization with the existing release barrier. The only
-- exception is the server-created resolver turn bound to this durable intent.
CREATE FUNCTION guard_agent_run_during_source_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status NOT IN ('SUCCEEDED','FAILED','CANCELLED') THEN
        PERFORM pg_advisory_xact_lock(814205002);
        IF EXISTS (SELECT 1 FROM mobile_source_update_operation op
            WHERE op.state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE')
              AND NOT COALESCE((((op.state='READY_TO_RESOLVE' AND op.resolver_run_id IS NULL)
                      OR op.resolver_run_id=NEW.id)
                  AND op.session_id=NEW.session_id AND op.resolver_turn_id=NEW.origin_turn_id
                  AND NEW.execution_target='REMOTE' AND NEW.workload_kind='project-codex-v4'
                  AND NEW.development_change_key::text=op.command_json->'owner'->>'changeKey'
                  AND NEW.change_source_revision=op.prepared_revision
                  AND NEW.change_base_commit=op.command_json->'owner'->>'baseCommit'
                  AND NEW.repository_commit=op.command_json->'owner'->>'sourceCommit'
                  AND NEW.change_expected_canonical_commit=NEW.repository_commit
                  AND NEW.selected_worker_id=op.command_json->'owner'->>'workerId'
                  AND NEW.workspace_identity=op.command_json->'owner'->>'workspaceIdentity'
                  AND NEW.change_source_fingerprint_sha256=op.preparation_json->>'preparedFingerprintSha256'
                  AND NEW.change_workspace_ownership_fingerprint_sha256=NEW.change_source_fingerprint_sha256), false)) THEN
            RAISE EXCEPTION 'SOURCE_UPDATE_IN_PROGRESS' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_run_source_update_admission BEFORE INSERT OR UPDATE OF status ON agent_run
    FOR EACH ROW EXECUTE FUNCTION guard_agent_run_during_source_update();

CREATE FUNCTION guard_validation_during_source_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='RUNNING' THEN
        PERFORM pg_advisory_xact_lock(814205002);
        IF EXISTS (SELECT 1 FROM mobile_source_update_operation
            WHERE state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE','RESOLVING')) THEN
            RAISE EXCEPTION 'SOURCE_UPDATE_IN_PROGRESS' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validation_source_update_admission BEFORE INSERT OR UPDATE OF status ON validation_operation
    FOR EACH ROW EXECUTE FUNCTION guard_validation_during_source_update();

CREATE FUNCTION guard_session_close_during_source_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.status IS DISTINCT FROM OLD.status OR NEW.remote_close_state IS DISTINCT FROM OLD.remote_close_state)
        AND EXISTS (SELECT 1 FROM mobile_source_update_operation WHERE session_id=NEW.id
            AND state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE','RESOLVING')) THEN
        RAISE EXCEPTION 'SOURCE_UPDATE_IN_PROGRESS' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER work_session_source_update_close BEFORE UPDATE OF status,remote_close_state ON work_session
    FOR EACH ROW EXECUTE FUNCTION guard_session_close_during_source_update();

CREATE FUNCTION guard_workspace_operation_during_source_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state IN ('REQUESTED','DISPATCHED') AND EXISTS (
        SELECT 1 FROM mobile_source_update_operation op JOIN work_session ws ON ws.id=op.session_id
        WHERE ws.development_change_id=NEW.development_change_id
            AND op.state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE','RESOLVING')) THEN
        RAISE EXCEPTION 'SOURCE_UPDATE_IN_PROGRESS' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER workspace_source_update_admission BEFORE INSERT OR UPDATE OF state ON development_change_workspace_operation
    FOR EACH ROW EXECUTE FUNCTION guard_workspace_operation_during_source_update();
