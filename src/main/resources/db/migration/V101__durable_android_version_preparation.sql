-- Preparing reviewed source version metadata is not APK publication or an AgentRun.
ALTER TABLE mobile_delivery_operation DROP CONSTRAINT mobile_delivery_operation_kind_check;
ALTER TABLE mobile_delivery_operation ADD CONSTRAINT mobile_delivery_operation_kind_check
    CHECK (kind IN ('PUBLISH_PR','INTEGRATE','RELEASE','FINALIZE','ANDROID_VERSION'));
DO $$ DECLARE constraint_name text; BEGIN
    SELECT conname INTO STRICT constraint_name FROM pg_constraint
      WHERE conrelid='mobile_delivery_operation'::regclass AND contype='c'
      AND pg_get_constraintdef(oid) LIKE '%kind%RELEASE%target%APP_PROD%';
    EXECUTE format('ALTER TABLE mobile_delivery_operation DROP CONSTRAINT %I',constraint_name);
END $$;
ALTER TABLE mobile_delivery_operation ADD CONSTRAINT mobile_delivery_kind_target_check
    CHECK (kind='RELEASE' OR (kind='ANDROID_VERSION' AND target='ANDROID_STABLE')
        OR (kind IN ('PUBLISH_PR','INTEGRATE','FINALIZE') AND target='APP_PROD'));
-- Keep the global publication mutex, also while preparing its version PR.
DROP INDEX mobile_delivery_one_global_release;
CREATE UNIQUE INDEX mobile_delivery_one_global_release ON mobile_delivery_operation ((1))
    WHERE kind IN ('RELEASE','ANDROID_VERSION') AND state NOT IN ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED');
CREATE FUNCTION guard_android_version_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.kind='ANDROID_VERSION' AND (
        (NEW.id,NEW.session_id,NEW.operator_id,NEW.kind,NEW.target,NEW.source_commit,NEW.execution_id,NEW.created_at)
        IS DISTINCT FROM
        (OLD.id,OLD.session_id,OLD.operator_id,OLD.kind,OLD.target,OLD.source_commit,OLD.execution_id,OLD.created_at)
        OR (NEW.evidence_json->'versionSource',NEW.evidence_json->'versionPredecessor',NEW.evidence_json->'versionCode',NEW.evidence_json->'versionName')
        IS DISTINCT FROM
        (OLD.evidence_json->'versionSource',OLD.evidence_json->'versionPredecessor',OLD.evidence_json->'versionCode',OLD.evidence_json->'versionName')) THEN
        RAISE EXCEPTION 'ATENEA_ANDROID_VERSION_INTENT_IMMUTABLE' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER android_version_intent_immutable BEFORE UPDATE ON mobile_delivery_operation
    FOR EACH ROW EXECUTE FUNCTION guard_android_version_intent();
COMMENT ON COLUMN mobile_delivery_operation.evidence_json IS
    'Durable receipts; ANDROID_VERSION retains fixed-file version PR, predecessor and original ticket integration; never publishes.';
