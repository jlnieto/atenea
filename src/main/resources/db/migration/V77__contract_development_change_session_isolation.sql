-- M2 contract migration. This migration is intentionally data-preserving.
-- Runtime flags, WS19/AX42 fingerprints and the prior synthetic proof are
-- checked by the sealed H1 preflight before Flyway is allowed to run.

LOCK TABLE work_session IN SHARE ROW EXCLUSIVE MODE;

DO $contract_preflight$
DECLARE
    active_project_index RECORD;
    active_change_index RECORD;
BEGIN
    IF to_regclass('development_change') IS NULL
            OR to_regclass('work_session') IS NULL THEN
        RAISE EXCEPTION 'M2 contract preflight: expanded schema is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM work_session
        WHERE development_change_id IS NULL
          AND status IN ('OPEN', 'CLOSING')
        GROUP BY project_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'M2 contract preflight: duplicate active legacy sessions';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM work_session
        WHERE development_change_id IS NOT NULL
          AND status IN ('OPEN', 'CLOSING')
        GROUP BY development_change_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'M2 contract preflight: duplicate active bound sessions';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM work_session session
        LEFT JOIN development_change change
          ON change.id = session.development_change_id
        WHERE session.development_change_id IS NOT NULL
          AND (
              change.id IS NULL
              OR session.project_id IS DISTINCT FROM change.project_id
              OR session.workspace_branch IS DISTINCT FROM change.workspace_branch
              OR session.workspace_identity IS DISTINCT FROM change.workspace_identity
              OR session.selected_worker_id IS DISTINCT FROM change.selected_worker_id
              OR session.execution_target IS DISTINCT FROM 'REMOTE'
              OR session.remote_session_id IS NULL
              OR session.remote_workload_kind IS DISTINCT FROM 'project-codex-v1'
          )
    ) THEN
        RAISE EXCEPTION 'M2 contract preflight: incomplete bound-session ownership';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM development_change
        GROUP BY project_id, workspace_branch
        HAVING count(*) > 1
    ) OR EXISTS (
        SELECT 1
        FROM development_change
        GROUP BY workspace_identity
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'M2 contract preflight: duplicate branch or workspace identity';
    END IF;

    SELECT indexrelid,
           indisunique,
           indisvalid,
           indisready,
           indnkeyatts,
           pg_get_indexdef(indexrelid) AS definition,
           pg_get_expr(indpred, indrelid) AS predicate
      INTO active_project_index
      FROM pg_index
     WHERE indexrelid = to_regclass('uk_work_session_active_project')
       AND indrelid = 'work_session'::regclass;

    IF NOT FOUND
            OR NOT active_project_index.indisunique
            OR NOT active_project_index.indisvalid
            OR NOT active_project_index.indisready
            OR active_project_index.indnkeyatts <> 1
            OR active_project_index.definition NOT LIKE '%USING btree (project_id)%'
            OR active_project_index.predicate IS NULL
            OR active_project_index.predicate NOT LIKE '%OPEN%'
            OR active_project_index.predicate NOT LIKE '%CLOSING%'
            OR active_project_index.predicate LIKE '%development_change_id%' THEN
        RAISE EXCEPTION 'M2 contract preflight: global active-project index is absent or divergent';
    END IF;

    SELECT indexrelid,
           indisunique,
           indisvalid,
           indisready,
           indnkeyatts,
           pg_get_indexdef(indexrelid) AS definition,
           pg_get_expr(indpred, indrelid) AS predicate
      INTO active_change_index
      FROM pg_index
     WHERE indexrelid = to_regclass('uk_work_session_active_change')
       AND indrelid = 'work_session'::regclass;

    IF NOT FOUND
            OR NOT active_change_index.indisunique
            OR NOT active_change_index.indisvalid
            OR NOT active_change_index.indisready
            OR active_change_index.indnkeyatts <> 1
            OR active_change_index.definition
                NOT LIKE '%USING btree (development_change_id)%'
            OR active_change_index.predicate IS NULL
            OR active_change_index.predicate NOT LIKE '%development_change_id IS NOT NULL%'
            OR active_change_index.predicate NOT LIKE '%OPEN%'
            OR active_change_index.predicate NOT LIKE '%CLOSING%' THEN
        RAISE EXCEPTION 'M2 contract preflight: active-change index is absent or divergent';
    END IF;
END
$contract_preflight$;

CREATE UNIQUE INDEX uk_work_session_active_legacy_project
    ON work_session (project_id)
    WHERE development_change_id IS NULL
      AND status IN ('OPEN', 'CLOSING');

DO $contract_index_check$
DECLARE
    active_change_index RECORD;
    active_legacy_index RECORD;
BEGIN
    SELECT indexrelid,
           indisunique,
           indisvalid,
           indisready,
           indnkeyatts,
           pg_get_indexdef(indexrelid) AS definition,
           pg_get_expr(indpred, indrelid) AS predicate
      INTO active_change_index
      FROM pg_index
     WHERE indexrelid = to_regclass('uk_work_session_active_change')
       AND indrelid = 'work_session'::regclass;

    SELECT indexrelid,
           indisunique,
           indisvalid,
           indisready,
           indnkeyatts,
           pg_get_indexdef(indexrelid) AS definition,
           pg_get_expr(indpred, indrelid) AS predicate
      INTO active_legacy_index
      FROM pg_index
     WHERE indexrelid = to_regclass('uk_work_session_active_legacy_project')
       AND indrelid = 'work_session'::regclass;

    IF active_change_index.indexrelid IS NULL
            OR NOT active_change_index.indisunique
            OR NOT active_change_index.indisvalid
            OR NOT active_change_index.indisready
            OR active_change_index.indnkeyatts <> 1
            OR active_change_index.definition
                NOT LIKE '%USING btree (development_change_id)%'
            OR active_change_index.predicate IS NULL
            OR active_change_index.predicate NOT LIKE '%development_change_id IS NOT NULL%'
            OR active_change_index.predicate NOT LIKE '%OPEN%'
            OR active_change_index.predicate NOT LIKE '%CLOSING%'
            OR active_legacy_index.indexrelid IS NULL
            OR NOT active_legacy_index.indisunique
            OR NOT active_legacy_index.indisvalid
            OR NOT active_legacy_index.indisready
            OR active_legacy_index.indnkeyatts <> 1
            OR active_legacy_index.definition NOT LIKE '%USING btree (project_id)%'
            OR active_legacy_index.predicate IS NULL
            OR active_legacy_index.predicate
                NOT LIKE '%development_change_id IS NULL%'
            OR active_legacy_index.predicate NOT LIKE '%OPEN%'
            OR active_legacy_index.predicate NOT LIKE '%CLOSING%' THEN
        RAISE EXCEPTION 'M2 contract preflight: isolation indexes are not exact';
    END IF;
END
$contract_index_check$;

DROP INDEX uk_work_session_active_project;

DO $contract_postflight$
BEGIN
    IF to_regclass('uk_work_session_active_project') IS NOT NULL
            OR to_regclass('uk_work_session_active_change') IS NULL
            OR to_regclass('uk_work_session_active_legacy_project') IS NULL THEN
        RAISE EXCEPTION 'M2 contract postflight: index transition is incomplete';
    END IF;
END
$contract_postflight$;
