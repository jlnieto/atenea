#!/usr/bin/env python3
"""One-off operator maintenance, not an App/AgentRun API.

Targets are the reviewed 2026-10-12 trial identities. --plan is read-only.
--apply requires root, stopped App, a verified resource-release receipt and
creates its own PostgreSQL 16 snapshot backup before any data mutation.
"""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import select
import socket
import stat
import subprocess
import tempfile


OPERATION = "change-only-20261012"
ARCHIVE = Path("/srv/atenea/backups/prod") / OPERATION
REAL_CHANGE = "59315b6e-59bc-4884-9def-356e1ca86ef4"
REAL_REMOTE = "e18f3461-1489-43cc-a7ad-99d60e84b8c5"
TRIAL_CHANGES = {
    1: "80c6340d-07b1-4304-8460-d3d3bafdffc8",
    2: "97f9901e-bccb-4cd8-97d8-8a9cb15f1054",
    3: "a1d00112-6b4f-4923-bcf5-6048fdfbb308",
    4: "707d87b1-2b04-4406-a3c5-a8f9f8c709e7",
    5: "a4167871-9106-4041-b1c9-d6a2c101ca15",
}
# id: (project, status, remote session); local identities are computed below.
TRIAL_SESSIONS = {
    2: (7, "CLOSED", None), 3: (7, "OPEN", None),
    4: (9, "OPEN", "2ac2a5fd-2981-49cf-8fac-8132e46a2d64"),
    6: (1, "DRAFT_BLOCKED", "c750641d-3226-44c3-81dc-d9149aac0de1"),
    7: (1, "CLOSED", "83356a20-421c-4d5f-8de6-05c98cce1c32"),
    8: (1, "CLOSED", "4dd2b77d-bd2f-4569-a5bd-3cd788541cd1"),
    9: (1, "CLOSED", "f8b7bd8d-5708-4c76-b206-e76d61fd8a89"),
    10: (1, "CLOSED", "6e54c056-f55a-44a6-b5fb-4fefbf7b247f"),
    11: (1, "CLOSED", "2d98d762-7d9b-4cc1-948f-5e6353dc8b76"),
    12: (1, "CLOSED", "075a8163-fced-43b6-8a5f-4b09ee7ee341"),
    13: (1, "CLOSED", "707c58a1-5105-45be-b7b7-bebd9bf8ab8e"),
    14: (1, "CLOSED", "8497e4e4-951e-477a-b457-cf91d341eed8"),
    15: (1, "CLOSED", "c80c1e72-e34f-46b9-ba34-5a9a0c0ad2d7"),
    16: (1, "CLOSED", "7151dce0-69ab-4614-86e4-f93f1af825e4"),
    17: (1, "CLOSED", "18c00753-6080-42f7-ac05-18c47b236cac"),
    19: (1, "CLOSED", "6547081d-895e-4be1-a8fd-d115b7743cdf"),
    20: (1, "CLOSED", "31f8c4bf-13bc-41ed-acd4-c7c7d45b92f5"),
}
TRIAL_RESCUES = {1: 7, 2: 1}
OWNED_TABLES = frozenset("""
work_session development_change rescue_session rescue_session_turn agent_run
agent_run_progress_event agent_run_recovery_operation session_turn
session_turn_attachment work_session_attachment work_session_preview
session_deliverable operator_session_read_state session_speech_briefing_cache
work_session_repository_role work_session_retirement fresh_work_session_operation
development_change_operation development_change_workspace_operation remote_session_operation
remote_close_legacy_plan remote_close_legacy_operation remote_close_legacy_event
validation_operation mobile_delivery_operation mobile_source_update_operation
mobile_source_resolver_retry mobile_source_recovery_authorization mobile_source_finalization
notification_event notification_delivery api_usage_record mobile_push_notification_log
voice_command_telemetry voice_note_send_intent core_command core_command_event
v2_audit_event v2_outbox_event project_verification_run
""".split())
RESET_TABLES = frozenset({"core_operator_context", "voice_focus", "voice_note"})
GUARDS = {
    "reject_terminal_development_change_operation_mutation":
        "7d5a1342ac56ef23a49515ce9a7dc04266d78642c959ece3684a698b5469851b",
    "reject_development_change_workspace_operation_mutation":
        "a583dbdd3d5d77bf4097726c175c4be9416330b8916f227c9d2c7b8bddca3696",
    "enforce_remote_session_operation_immutability":
        "341b15974fcb263cae2587b86bcd017002ad22a104395009272e410f9bbde70a",
    "reject_v2_audit_event_mutation":
        "161bbd816b26848c682d2de4e6e224638fea5d3d78fde5f90471c0c39796ee21",
}
GUARD_TABLES = {
    "reject_terminal_development_change_operation_mutation": "development_change_operation",
    "reject_development_change_workspace_operation_mutation": "development_change_workspace_operation",
    "enforce_remote_session_operation_immutability": "remote_session_operation",
    "reject_v2_audit_event_mutation": "v2_audit_event",
}
SESSION_COLUMNS = {"session_id", "work_session_id", "source_work_session_id", "result_work_session_id", "replacement_work_session_id"}
RUN_COLUMNS = {"agent_run_id", "run_id", "source_agent_run_id", "source_run_id", "result_agent_run_id"}
TURN_COLUMNS = {"session_turn_id", "resolver_turn_id", "origin_turn_id", "result_turn_id"}
ACTIVE = {"RUNNING", "QUEUED", "STARTING", "CANCELLING", "REQUESTED", "DISPATCHED", "APPLYING", "IN_PROGRESS", "RECONCILING", "SOURCE_RELEASED"}


class Refused(RuntimeError):
    pass


def require(condition, code):
    if not condition:
        raise Refused(code)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def identifier(value):
    require(bool(re.fullmatch(r"[a-z][a-z0-9_]*", value)), "UNEXPECTED_SQL_IDENTITY")
    return '"' + value + '"'


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def row_key(table, row, primary_keys):
    require(bool(primary_keys.get(table)), "TABLE_WITHOUT_REVIEWABLE_KEY")
    return tuple(row[c] for c in primary_keys[table])


def validate_targets(rows):
    sessions = {r["id"]: r for r in rows["work_session"]}
    changes = {r["id"]: r for r in rows["development_change"]}
    rescues = {r["id"]: r for r in rows["rescue_session"]}
    real = sessions.get(21, {})
    require(real.get("project_id") == 1 and real.get("development_change_id") == 6
            and real.get("status") == "CLOSED" and real.get("remote_session_id") == REAL_REMOTE
            and real.get("workspace_branch") == "atenea/change-" + REAL_CHANGE
            and real.get("workspace_identity") == "remote:ax42-01:change:" + REAL_CHANGE
            and real.get("remote_close_state") == "RELEASED", "REAL_SESSION_NOT_EXACT")
    dc = changes.get(6, {})
    require(dc.get("change_key") == REAL_CHANGE and dc.get("project_id") == 1
            and dc.get("status") == "COMPLETED", "REAL_CHANGE_NOT_EXACT")
    present = [sid in sessions for sid in TRIAL_SESSIONS]
    present += [cid in changes for cid in TRIAL_CHANGES]
    present += [rid in rescues for rid in TRIAL_RESCUES]
    require(all(present) or not any(present), "PARTIALLY_PURGED_TARGETS")
    for sid, (project, status, remote) in TRIAL_SESSIONS.items():
        if sid not in sessions:
            continue
        ws = sessions[sid]
        key = TRIAL_CHANGES[4] if sid == 20 else None
        identity = ("remote:ax42-01:change:" + key if key else
                    "remote:ax42-01:work-session:" + remote if remote else f"local:work-session:{sid}")
        branch = "atenea/change-" + key if key else "atenea/session-" + (remote or str(sid))
        require(ws.get("project_id") == project and ws.get("status") == status
                and ws.get("remote_session_id") == remote and ws.get("workspace_identity") == identity
                and ws.get("workspace_branch") == branch
                and ws.get("development_change_id") == (4 if sid == 20 else None)
                and ws.get("selected_worker_id") == ("ax42-01" if remote else None),
                "TRIAL_SESSION_IDENTITY_MOVED")
    for cid, key in TRIAL_CHANGES.items():
        if cid in changes:
            dc = changes[cid]
            require(dc.get("change_key") == key and dc.get("project_id") == 1
                    and dc.get("status") == "ABANDONED"
                    and dc.get("selected_worker_id") == "ax42-01"
                    and dc.get("workspace_branch") == "atenea/change-" + key
                    and dc.get("workspace_identity") == "remote:ax42-01:change:" + key,
                    "TRIAL_CHANGE_IDENTITY_MOVED")
    for rid, project in TRIAL_RESCUES.items():
        if rid in rescues:
            require(rescues[rid].get("project_id") == project and rescues[rid].get("status") == "OPEN"
                    and rescues[rid].get("title") == "Rescate operativo", "TRIAL_RESCUE_IDENTITY_MOVED")
    require(all(r.get("development_change_id") is not None or r["id"] in TRIAL_SESSIONS
                for r in sessions.values()), "UNREVIEWED_STANDALONE_SESSION")
    require(set(rescues).issubset(TRIAL_RESCUES), "UNREVIEWED_RESCUE")
    return any(present)


def make_plan(rows, foreign_keys, primary_keys):
    """Closed descendants only. Never select parents (projects/users/workers).

    FK discovery detects new dependencies; it is not authority to delete new
    tables or cross-owned rows. All retained rows get exact post-state checks.
    """
    dirty = validate_targets(rows)
    for table, entries in rows.items():
        for row in entries:
            if table in {"agent_run", "validation_operation", "rescue_session"}:
                require(row.get("status") not in ACTIVE, "NON_TERMINAL_EXECUTION")
            if table in {"development_change_operation", "development_change_workspace_operation",
                         "remote_session_operation", "mobile_delivery_operation", "work_session_retirement",
                         "mobile_source_update_operation", "mobile_source_resolver_retry", "mobile_source_finalization",
                         "fresh_work_session_operation", "remote_close_legacy_operation"}:
                require(row.get("state", row.get("status")) not in ACTIVE, "NON_TERMINAL_OPERATION")
    selected = {t: set() for t in rows}
    expected = copy.deepcopy(rows)
    trial_runs = {r["id"] for r in rows.get("agent_run", []) if r["session_id"] in TRIAL_SESSIONS}
    trial_turns = {r["id"] for r in rows.get("session_turn", []) if r["session_id"] in TRIAL_SESSIONS}

    def add(table, row):
        require(table in OWNED_TABLES, "UNREVIEWED_DEPENDENT_TABLE")
        anchors = [v for k, v in row.items() if k in SESSION_COLUMNS and v is not None]
        if table == "work_session":
            anchors = [row["id"]]
        require(all(v in TRIAL_SESSIONS for v in anchors), "CROSS_OWNED_DEPENDENCY")
        require(all(row[c] in trial_runs for c in RUN_COLUMNS if row.get(c) is not None),
                "CROSS_OWNED_RUN_DEPENDENCY")
        require(all(row[c] in trial_turns for c in TURN_COLUMNS if row.get(c) is not None),
                "CROSS_OWNED_TURN_DEPENDENCY")
        require(row.get("development_change_key") is None
                or row["development_change_key"] in TRIAL_CHANGES.values(), "CROSS_OWNED_CHANGE_DEPENDENCY")
        if table in {"development_change", "development_change_operation",
                     "development_change_workspace_operation", "remote_session_operation"}:
            cid = row.get("development_change_id", row["id"] if table == "development_change" else None)
            require(cid is None or cid in TRIAL_CHANGES, "CROSS_OWNED_CHANGE_DEPENDENCY")
        key = row_key(table, row, primary_keys)
        added = key not in selected[table]
        selected[table].add(key)
        return added

    for table, ids in (("work_session", TRIAL_SESSIONS), ("development_change", TRIAL_CHANGES),
                       ("rescue_session", TRIAL_RESCUES)):
        for row in rows[table]:
            if row["id"] in ids:
                add(table, row)

    changed = True
    while changed:
        changed = False
        run_ids = {r["id"] for r in rows.get("agent_run", []) if row_key("agent_run", r, primary_keys) in selected["agent_run"]}
        turn_ids = {r["id"] for r in rows.get("session_turn", []) if row_key("session_turn", r, primary_keys) in selected["session_turn"]}
        command_ids = {r["id"] for r in rows.get("core_command", []) if row_key("core_command", r, primary_keys) in selected["core_command"]}
        operation_ids = {r["operation_id"] for t in OWNED_TABLES for r in rows.get(t, [])
                         if "operation_id" in r and row_key(t, r, primary_keys) in selected[t]}
        for table in OWNED_TABLES:
            for row in rows.get(table, []):
                hit = any(row.get(c) in TRIAL_SESSIONS for c in SESSION_COLUMNS)
                hit |= any(row.get(c) in run_ids for c in RUN_COLUMNS)
                hit |= any(row.get(c) in turn_ids for c in TURN_COLUMNS)
                if table in {"api_usage_record", "voice_command_telemetry"}:
                    hit |= row.get("core_command_id", row.get("active_command_id")) in command_ids
                if table == "core_command":
                    hit |= row.get("target_type") == "WORK_SESSION" and row.get("target_id") in TRIAL_SESSIONS
                    hit |= row.get("target_type") == "SESSION_TURN" and row.get("target_id") in turn_ids
                    hit |= row.get("target_type") == "RESCUE_SESSION" and row.get("target_id") in TRIAL_RESCUES
                    for field in ("request_context_json", "parameters_json", "interpreted_intent_json"):
                        if row.get(field):
                            data = json.loads(row[field])
                            refs = context_sessions(data)
                            if refs & TRIAL_SESSIONS.keys():
                                require(refs.issubset(TRIAL_SESSIONS), "CROSS_OWNED_COMMAND_CONTEXT")
                                hit = True
                if table == "v2_audit_event":
                    hit |= row.get("operation_id") in operation_ids
                if hit:
                    changed |= add(table, row)
        for fk in foreign_keys:
            parents = {tuple(r[c] for c in fk["parentColumns"]) for r in rows.get(fk["parent"], [])
                       if row_key(fk["parent"], r, primary_keys) in selected.get(fk["parent"], set())}
            if not parents:
                continue
            require(fk["table"] in rows, "UNREVIEWED_DEPENDENT_TABLE")
            for row in rows[fk["table"]]:
                values = tuple(row[c] for c in fk["columns"])
                if None in values or values not in parents:
                    continue
                if fk["deleteAction"] == "n" and fk["table"] in RESET_TABLES:
                    continue
                changed |= add(fk["table"], row)

    # Model SET NULL precisely without erasing shared operator context or notes.
    for fk in foreign_keys:
        if fk["deleteAction"] != "n":
            continue
        parents = {tuple(r[c] for c in fk["parentColumns"]) for r in rows.get(fk["parent"], [])
                   if row_key(fk["parent"], r, primary_keys) in selected.get(fk["parent"], set())}
        for row in expected.get(fk["table"], []):
            values = tuple(row[c] for c in fk["columns"])
            if None not in values and values in parents and row_key(fk["table"], row, primary_keys) not in selected[fk["table"]]:
                require(fk["table"] in RESET_TABLES, "UNREVIEWED_SHARED_RESET")
                for column in fk["columns"]:
                    row[column] = None

    targets = {t: [r for r in entries if row_key(t, r, primary_keys) in selected[t]] for t, entries in rows.items()}
    retained = {t: sorted([r for r in entries if row_key(t, r, primary_keys) not in selected[t]], key=canonical)
                for t, entries in expected.items()}
    require(dirty or not any(targets.values()), "RESIDUAL_TRIAL_DEPENDENCIES")
    return {"operation": OPERATION, "state": "READY" if dirty else "ALREADY_CLEAN",
            "targets": targets, "retained": retained, "primaryKeys": primary_keys,
            "foreignKeys": foreign_keys, "counts": {t: len(r) for t, r in targets.items() if r},
            "protectedSha256": digest(retained)}


def context_sessions(data):
    result = set()
    if isinstance(data, dict):
        for key, value in data.items():
            if key in {"workSessionId", "activeWorkSessionId", "work_session_id", "sessionId"} and value is not None:
                require(isinstance(value, int) and not isinstance(value, bool), "UNEXPECTED_COMMAND_CONTEXT")
                result.add(value)
            else:
                result |= context_sessions(value)
    elif isinstance(data, list):
        for value in data:
            result |= context_sessions(value)
    return result


def ordered_deletes(plan):
    """Leaves first, including self-retry/predecessor chains; unknown cycles stop."""
    pk = plan["primaryKeys"]
    remaining = {(t, row_key(t, r, pk)): r for t, entries in plan["targets"].items() for r in entries}
    result = []
    while remaining:
        referenced = set()
        for fk in plan["foreignKeys"]:
            if fk["name"] == "fk_remote_close_legacy_plan_consumed_operation":
                continue  # The sole reviewed nullable cycle is detached after backup.
            for (table, _), child in remaining.items():
                if table != fk["table"]:
                    continue
                values = tuple(child[c] for c in fk["columns"])
                if None in values:
                    continue
                for (parent_table, key), parent in remaining.items():
                    if parent_table == fk["parent"] and values == tuple(parent[c] for c in fk["parentColumns"]):
                        referenced.add((parent_table, key))
        leaves = sorted(set(remaining) - referenced, key=str)
        require(bool(leaves), "UNREVIEWED_REFERENCE_CYCLE")
        for node in leaves:
            result.append((node[0], remaining.pop(node)))
    return result


class Psql:
    def __init__(self, command):
        self.errors = tempfile.TemporaryFile()
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.errors, text=True, bufsize=1)

    def query(self, sql):
        self.process.stdin.write(sql.rstrip(";\n") + ";\n")
        self.process.stdin.flush()
        require(bool(select.select([self.process.stdout], [], [], 40)[0]), "DATABASE_TIMEOUT")
        line = self.process.stdout.readline()
        require(bool(line), "DATABASE_COMMAND_FAILED")
        return json.loads(line)

    def execute(self, sql):
        return self.query(sql.rstrip(";\n") + "; SELECT json_build_object('ok',true)")

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            self.process.wait(timeout=5)
        self.errors.close()


def inspect_database(db, schema="public"):
    ns = literal(schema)
    tables = db.query("SELECT coalesce(json_agg(relname ORDER BY relname),'[]') FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=" + ns + " AND c.relkind='r'")
    pks = db.query("""SELECT coalesce(json_agg(json_build_object('table',r.relname,'columns',
        (SELECT json_agg(a.attname ORDER BY k.ord) FROM unnest(c.conkey) WITH ORDINALITY k(num,ord)
         JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num)) ORDER BY r.relname),'[]')
        FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid JOIN pg_namespace n ON n.oid=r.relnamespace
        WHERE c.contype='p' AND n.nspname=""" + ns)
    fks = db.query("""SELECT coalesce(json_agg(json_build_object('name',c.conname,'table',r.relname,'parent',p.relname,'parentSchema',pn.nspname,
        'deleteAction',c.confdeltype,'columns',(SELECT json_agg(a.attname ORDER BY k.ord)
          FROM unnest(c.conkey) WITH ORDINALITY k(num,ord) JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num),
        'parentColumns',(SELECT json_agg(a.attname ORDER BY k.ord) FROM unnest(c.confkey) WITH ORDINALITY k(num,ord)
          JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num)) ORDER BY r.relname,c.conname),'[]')
        FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid JOIN pg_class p ON p.oid=c.confrelid
        JOIN pg_namespace n ON n.oid=r.relnamespace JOIN pg_namespace pn ON pn.oid=p.relnamespace
        WHERE c.contype='f' AND n.nspname=""" + ns)
    require(all(fk["parentSchema"] == schema for fk in fks if fk["parent"] in OWNED_TABLES | RESET_TABLES),
            "CROSS_SCHEMA_DEPENDENCY")
    rows, unrelated = {}, {}
    for table in tables:
        relation = identifier(schema) + "." + identifier(table)
        if table in OWNED_TABLES | RESET_TABLES:
            rows[table] = db.query("SELECT coalesce(json_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]') FROM " + relation + " t")
        else:
            # Hash in PostgreSQL; never retrieve account/token/secret payloads.
            unrelated[table] = db.query("SELECT to_json(encode(sha256(convert_to(coalesce(string_agg(to_jsonb(t)::text,E'\\n' ORDER BY to_jsonb(t)::text),''),'UTF8')),'hex')) FROM " + relation + " t")
    return rows, fks, {p["table"]: p["columns"] for p in pks}, unrelated


def install_scoped_delete_permission(db, plan, schema="public"):
    """Keep triggers enabled. Only exact frozen trial rows may be deleted.

    The permission exists solely in this uncommitted, locked transaction. The
    original function definitions are restored byte-for-byte before COMMIT;
    concurrent App connections never observe a weakened committed guard.
    """
    originals = verify_guards(db, schema)
    db.execute("CREATE TEMP TABLE change_only_exact_deletes(table_name text, row_json jsonb) ON COMMIT DROP")
    for table, entries in plan["targets"].items():
        for row in entries:
            db.execute("INSERT INTO pg_temp.change_only_exact_deletes VALUES (" + literal(table) + "," + literal(canonical(row)) + "::jsonb)")
    for name, value in originals.items():
        permission = ("\nBEGIN\n    IF TG_OP='DELETE' AND TG_TABLE_SCHEMA=" + literal(schema) +
                      " AND EXISTS (SELECT 1 FROM pg_temp.change_only_exact_deletes"
                      " WHERE table_name=TG_TABLE_NAME AND row_json=to_jsonb(OLD)) THEN RETURN OLD; END IF;\n")
        new_source = value["source"].replace("\nBEGIN\n", permission, 1)
        require(value["definition"].count(value["source"]) == 1, "UNEXPECTED_GUARD_DEFINITION")
        db.execute(value["definition"].replace(value["source"], new_source, 1))
    return originals


def verify_guards(db, schema="public"):
    originals = {}
    for name, expected_hash in GUARDS.items():
        qualified = identifier(schema) + "." + identifier(name)
        value = db.query("SELECT json_build_object('source',prosrc,'definition',pg_get_functiondef(oid)) FROM pg_proc WHERE oid=" + literal(qualified + "()") + "::regprocedure")
        require(hashlib.sha256(value["source"].encode()).hexdigest() == expected_hash,
                "IMMUTABILITY_GUARD_MOVED")
        require(value["source"].count("\nBEGIN\n") == 1, "UNEXPECTED_GUARD_STRUCTURE")
        triggers = db.query("SELECT coalesce(json_agg(json_build_object('table',c.relname,'schema',n.nspname,'enabled',t.tgenabled)),'[]') FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE t.tgfoid=" + literal(qualified + "()") + "::regprocedure")
        require(triggers == [{"table": GUARD_TABLES[name], "schema": schema, "enabled": "O"}],
                "IMMUTABILITY_TRIGGER_MOVED")
        originals[name] = value
    return originals


def apply_plan(db, plan, schema="public"):
    order = ordered_deletes(plan)  # Reject unknown cycles BEFORE permissions/mutations.
    exact_retained = retained_hashes(db, plan, schema)
    require(exact_retained == plan.setdefault("exactRetained", exact_retained), "RETAINED_DATA_MOVED")
    originals = install_scoped_delete_permission(db, plan, schema)
    for row in plan["targets"].get("remote_close_legacy_plan", []):
        if row.get("consumed_by_operation_id") is not None:
            require(any(op.get("operation_id") == row["consumed_by_operation_id"]
                        for op in plan["targets"].get("remote_close_legacy_operation", [])),
                    "FOREIGN_LEGACY_CONSUMPTION")
            db.execute("UPDATE " + identifier(schema) + ".remote_close_legacy_plan SET consumed_at=NULL, consumed_by_operation_id=NULL, confirmation_idempotency_key=NULL WHERE id=" + literal(row["id"]))
    for table, row in order:
        relation = identifier(schema) + "." + identifier(table)
        predicate = " AND ".join(identifier(c) + "=" + literal(row[c]) for c in plan["primaryKeys"][table])
        value = db.query("WITH removed AS (DELETE FROM " + relation + " WHERE " + predicate + " RETURNING 1) SELECT to_json(count(*)) FROM removed")
        require(value == 1, "DELETE_TARGET_CHANGED")
    for name, value in originals.items():
        db.execute(value["definition"])
        restored = db.query("SELECT to_json(pg_get_functiondef(" + literal(identifier(schema) + "." + identifier(name) + "()") + "::regprocedure))")
        require(restored == value["definition"], "GUARD_RESTORE_FAILED")
    rows, fks, pks, unrelated = inspect_database(db, schema)
    require(fks == plan["foreignKeys"] and pks == plan["primaryKeys"], "DATABASE_CONTRACT_CHANGED")
    require(make_plan(rows, fks, pks)["state"] == "ALREADY_CLEAN", "CLEANUP_POSTCONDITION_FAILED")
    require(digest({t: sorted(r, key=canonical) for t, r in rows.items()}) == plan["protectedSha256"], "RETAINED_DATA_CHANGED")
    require(retained_hashes(db, plan, schema) == exact_retained, "EXACT_RETAINED_DATA_CHANGED")
    require(unrelated == plan["unrelated"], "UNRELATED_DATA_CHANGED")
    return {"state": "VERIFIED", "protectedSha256": plan["protectedSha256"], "counts": plan["counts"]}


def retained_hashes(db, plan, schema="public"):
    """Hash exact PostgreSQL values, without numeric rounding through JSON/Python.

    The three shared SET NULL context tables are separately checked against
    their exact modeled post-state; all other retained owned rows are immutable.
    """
    result = {}
    for table, entries in plan["targets"].items():
        if table in RESET_TABLES:
            continue
        excluded = ["(" + " AND ".join("t." + identifier(col) + "=" + literal(row[col])
                    for col in plan["primaryKeys"][table]) + ")" for row in entries]
        predicate = " WHERE NOT (" + " OR ".join(excluded) + ")" if excluded else ""
        result[table] = db.query("SELECT to_json(encode(sha256(convert_to(coalesce(string_agg(to_jsonb(t)::text,E'\\n' ORDER BY to_jsonb(t)::text),''),'UTF8')),'hex')) FROM " + identifier(schema) + "." + identifier(table) + " t" + predicate)
    return result


def authority(path, directory=False):
    info = path.lstat()
    require(not stat.S_ISLNK(info.st_mode) and info.st_uid == info.st_gid == 0
            and not info.st_mode & 0o022, "ROOT_AUTHORITY_REQUIRED")
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode) and info.st_nlink == 1,
            "UNEXPECTED_FILE_STRUCTURE")


def write_private(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as out:
        out.write(canonical(value) + "\n")
        out.flush()
        os.fsync(out.fileno())


def snapshot_backup(db):
    for parent in [Path("/srv"), Path("/srv/atenea"), Path("/srv/atenea/backups"), ARCHIVE.parent]:
        authority(parent, True)
    ARCHIVE.mkdir(mode=0o700, exist_ok=False)
    authority(ARCHIVE, True)
    snapshot = db.query("SELECT to_json(pg_export_snapshot())")
    require(bool(re.fullmatch(r"[0-9A-F]+-[0-9A-F]+-[0-9]+", snapshot)), "UNEXPECTED_SNAPSHOT")
    version = subprocess.check_output(["docker", "exec", "atenea-postgres-prod", "pg_dump", "--version"], text=True)
    require(" 16." in version, "POSTGRESQL_16_REQUIRED")
    dump = ARCHIVE / "atenea_prod.dump"
    fd = os.open(dump, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as out:
        subprocess.run(["docker", "exec", "atenea-postgres-prod", "/bin/sh", "-c",
                        'exec pg_dump -U "$POSTGRES_USER" -d atenea_prod --format=custom --snapshot="$1"',
                        "snapshot-backup", snapshot], stdout=out, stderr=subprocess.DEVNULL, check=True, timeout=180)
        out.flush()
        os.fsync(out.fileno())
    hash_value = hashlib.sha256()
    with dump.open("rb") as data:
        for chunk in iter(lambda: data.read(1024 * 1024), b""):
            hash_value.update(chunk)
    checksum = hash_value.hexdigest()
    with dump.open("rb") as data:
        subprocess.run(["docker", "exec", "-i", "atenea-postgres-prod", "pg_restore", "--list"],
                       stdin=data, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=60)
    result = {"snapshot": snapshot, "sha256": checksum, "size": dump.stat().st_size, "structure": "PASS",
              "createdAt": datetime.now(timezone.utc).isoformat()}
    write_private(ARCHIVE / "backup.json", result)
    return result


def execute_verified_purge(db, plan, schema="public"):
    # Backup failure must occur before any trial row/function can be changed.
    backup = snapshot_backup(db)
    write_private(ARCHIVE / "plan.json", plan)
    result = apply_plan(db, plan, schema)
    write_private(ARCHIVE / "verified-before-commit.json", result)
    db.execute("COMMIT")
    write_private(ARCHIVE / "committed.json", {**result, "operation": OPERATION, "state": "COMMITTED", "backup": backup})
    return result


def reconcile_commit_evidence(plan):
    """A lost COMMIT acknowledgement never means a second DELETE.

    Verify the frozen plan, the pre-COMMIT result, current data/guards and dump
    before sealing a missing acknowledgement. No database mutation here.
    """
    for parent in [Path("/srv"), Path("/srv/atenea"), Path("/srv/atenea/backups"), ARCHIVE.parent, ARCHIVE]:
        authority(parent, True)
    frozen_path, verified_path, backup_path = (ARCHIVE / name for name in
                                              ("plan.json", "verified-before-commit.json", "backup.json"))
    for path in (frozen_path, verified_path, backup_path, ARCHIVE / "atenea_prod.dump"):
        authority(path)
    frozen = json.loads(frozen_path.read_text())
    verified = json.loads(verified_path.read_text())
    backup = json.loads(backup_path.read_text())
    require(frozen.get("operation") == OPERATION and frozen.get("state") == "READY"
            and verified.get("state") == "VERIFIED"
            and frozen.get("protectedSha256") == verified.get("protectedSha256") == plan["protectedSha256"]
            and frozen.get("exactRetained") == plan["exactRetained"]
            and frozen.get("unrelated") == plan["unrelated"]
            and frozen.get("counts") == verified.get("counts"), "REPLAY_EVIDENCE_MOVED")
    hash_value = hashlib.sha256()
    with (ARCHIVE / "atenea_prod.dump").open("rb") as data:
        for chunk in iter(lambda: data.read(1024 * 1024), b""):
            hash_value.update(chunk)
    require(backup.get("structure") == "PASS" and backup.get("sha256") == hash_value.hexdigest()
            and backup.get("size") == (ARCHIVE / "atenea_prod.dump").stat().st_size, "REPLAY_BACKUP_MISMATCH")
    committed = ARCHIVE / "committed.json"
    if committed.exists():
        authority(committed)
        prior = json.loads(committed.read_text())
        require(prior.get("operation") == OPERATION and prior.get("state") == "COMMITTED"
                and prior.get("protectedSha256") == plan["protectedSha256"]
                and prior.get("backup") == backup, "REPLAY_COMMIT_MISMATCH")
        return "ALREADY_CLEAN"
    write_private(committed, {**verified, "operation": OPERATION, "state": "COMMITTED", "backup": backup,
                              "acknowledgementReconciled": True})
    return "COMMIT_ACKNOWLEDGEMENT_RECONCILED"


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--plan", action="store_true")
    mode.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    require(socket.gethostname() == "atenea", "FIXED_VPS_REQUIRED")
    if args.apply:
        require(os.geteuid() == 0, "ROOT_OPERATOR_REQUIRED")
        source = Path(__file__).absolute()
        authority(Path(__file__))
        authority(source)
        for parent in source.parents:
            authority(parent, True)
        backend = json.loads(subprocess.check_output(["docker", "inspect", "--format", "{{json .State}}", "atenea-backend-prod"], text=True))
        require(not backend["Running"], "APP_MAINTENANCE_REQUIRED")
        # This is produced by the reviewed Platform resource cleanup, not a client.
        receipt_path = Path("/etc/atenea-release-v1/change-only-resources.json")
        for parent in receipt_path.parents:
            authority(parent, True)
        authority(receipt_path)
        receipt = json.loads(receipt_path.read_text())
        require(receipt.get("operation") == OPERATION and receipt.get("state") == "RELEASED"
                and receipt.get("schemaVersion") == "change-only-resource-cleanup-receipt-v1"
                and receipt.get("preservedChangeKey") == REAL_CHANGE
                and receipt.get("trialSessionIds") == sorted(TRIAL_SESSIONS)
                and receipt.get("trialChangeKeys") == sorted(TRIAL_CHANGES.values())
                and receipt.get("nonTerminalExecutions") == 0
                and bool(re.fullmatch(r"[0-9a-f]{64}", receipt.get("archiveSha256", ""))),
                "RESOURCE_RELEASE_EVIDENCE_REQUIRED")
    command = ["docker", "exec", "-i", "atenea-postgres-prod", "/bin/sh", "-c",
               'exec psql -X -U "$POSTGRES_USER" -d atenea_prod -qAt -v ON_ERROR_STOP=1']
    db = Psql(command)
    try:
        db.execute("BEGIN ISOLATION LEVEL " + ("SERIALIZABLE" if args.apply else "REPEATABLE READ READ ONLY"))
        db.execute("SET LOCAL lock_timeout='5s'; SET LOCAL statement_timeout='30s'; SET LOCAL standard_conforming_strings=on")
        if args.apply:
            tables = db.query("SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public'")
            db.execute("LOCK TABLE " + ",".join("public." + identifier(t) for t in tables) + " IN SHARE ROW EXCLUSIVE MODE")
        rows, fks, pks, unrelated = inspect_database(db)
        verify_guards(db)
        plan = make_plan(rows, fks, pks)
        plan["unrelated"] = unrelated
        plan["exactRetained"] = retained_hashes(db, plan)
        ordered_deletes(plan)
        if args.plan:
            db.execute("ROLLBACK")
            print(canonical({**{k: plan[k] for k in ("operation", "state", "counts", "protectedSha256")},
                             "scope": "DATABASE_ONLY", "applied": False, "resourceReleaseRequired": True}))
            return
        if plan["state"] == "ALREADY_CLEAN":
            result = reconcile_commit_evidence(plan)
            db.execute("ROLLBACK")
            print(canonical({"operation": OPERATION, "state": result, "newDatabaseMutations": 0}))
            return
        result = execute_verified_purge(db, plan)
        print(canonical({"operation": OPERATION, "state": "COMMITTED", "counts": result["counts"]}))
    finally:
        # Disconnecting before COMMIT rolls back data and function definitions.
        db.close()


if __name__ == "__main__":
    try:
        main()
    except (Refused, OSError, ValueError, subprocess.SubprocessError) as exc:
        print("CLEANUP_STOPPED: " + (str(exc) if isinstance(exc, Refused) else type(exc).__name__))
        raise SystemExit(1)
