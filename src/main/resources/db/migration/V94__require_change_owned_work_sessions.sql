-- Contract the work model only AFTER the reviewed operator cleanup.
-- Never adopt, relabel or silently delete retained standalone sessions here.
LOCK TABLE work_session IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM work_session WHERE development_change_id IS NULL) THEN
        RAISE EXCEPTION 'ATENEA_CHANGE_ONLY_CLEANUP_REQUIRED' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        WHERE c.conname='fk_work_session_development_change_project'
          AND c.conrelid='work_session'::regclass
          AND c.confrelid='development_change'::regclass
          AND c.contype='f' AND c.convalidated AND c.confdeltype='r'
          AND c.conkey=ARRAY[
              (SELECT attnum FROM pg_attribute WHERE attrelid=c.conrelid AND attname='development_change_id'),
              (SELECT attnum FROM pg_attribute WHERE attrelid=c.conrelid AND attname='project_id')]
          AND c.confkey=ARRAY[
              (SELECT attnum FROM pg_attribute WHERE attrelid=c.confrelid AND attname='id'),
              (SELECT attnum FROM pg_attribute WHERE attrelid=c.confrelid AND attname='project_id')]
    ) THEN
        RAISE EXCEPTION 'ATENEA_CHANGE_ONLY_PROJECT_BINDING_REQUIRED' USING ERRCODE='23514';
    END IF;
END;
$$;

ALTER TABLE work_session ALTER COLUMN development_change_id SET NOT NULL;
DROP INDEX uk_work_session_active_legacy_project;
