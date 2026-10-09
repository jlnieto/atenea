-- Each continuation retains its exact immutable predecessor and a separately pinned main.
ALTER TABLE mobile_source_update_operation ADD COLUMN predecessor_id UUID REFERENCES mobile_source_update_operation(id);
DO $$
DECLARE constraint_name text;
BEGIN
    SELECT c.conname INTO STRICT constraint_name FROM pg_constraint c
    WHERE c.conrelid='mobile_source_update_operation'::regclass AND c.contype='u'
        AND (SELECT array_agg(a.attname::text ORDER BY x.ordinality)
            FROM unnest(c.conkey) WITH ORDINALITY x(attnum,ordinality)
            JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=x.attnum)
            =ARRAY['session_id','publication_receipt_sha256'];
    EXECUTE format('ALTER TABLE mobile_source_update_operation DROP CONSTRAINT %I',constraint_name);
END $$;
CREATE UNIQUE INDEX source_update_one_continuation ON mobile_source_update_operation(predecessor_id) WHERE predecessor_id IS NOT NULL;
CREATE UNIQUE INDEX source_update_one_initial ON mobile_source_update_operation(session_id) WHERE predecessor_id IS NULL;

CREATE FUNCTION guard_source_preparation_lineage() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent mobile_source_update_operation;
BEGIN
    PERFORM pg_advisory_xact_lock(814205002);
    IF TG_OP='UPDATE' THEN
        IF NEW.predecessor_id IS DISTINCT FROM OLD.predecessor_id THEN
            RAISE EXCEPTION 'SOURCE_UPDATE_IMMUTABLE_PREDECESSOR' USING ERRCODE='55000';
        END IF;
    ELSIF NEW.predecessor_id IS NOT NULL THEN
        SELECT * INTO parent FROM mobile_source_update_operation WHERE id=NEW.predecessor_id;
        IF NOT COALESCE(parent.session_id=NEW.session_id AND parent.state IN ('RESOLVER_COMPLETED','READY_TO_FINALIZE','PUBLISHED')
            AND NEW.state='QUEUED' AND NEW.command_json->>'predecessorPreparationOperationId'=parent.id::text
            AND NEW.command_json->>'predecessorPreparationReceiptSha256'=parent.preparation_json->>'receiptSha256'
            AND NEW.command_json->>'targetMainCommit'<>parent.command_json->>'targetMainCommit',false) THEN
            RAISE EXCEPTION 'SOURCE_UPDATE_PREDECESSOR_NOT_AUTHORIZED' USING ERRCODE='55000';
        END IF;
    ELSIF NEW.command_json->>'predecessorPreparationOperationId' IS NOT NULL THEN
        RAISE EXCEPTION 'SOURCE_UPDATE_PREDECESSOR_NOT_AUTHORIZED' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_preparation_lineage BEFORE INSERT OR UPDATE ON mobile_source_update_operation
    FOR EACH ROW EXECUTE FUNCTION guard_source_preparation_lineage();
