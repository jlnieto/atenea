#!/usr/bin/env python3
"""Real PostgreSQL tests. Launched by ChangeOnlyCleanupIntegrationTest in an isolated schema."""
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

spec = importlib.util.spec_from_file_location("cleanup", Path(__file__).with_name("change-only-cleanup.py"))
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)


class CleanupTest(unittest.TestCase):
    def setUp(self):
        self.schema = os.environ["ATENEA_CLEANUP_TEST_SCHEMA"]
        self.assertRegex(self.schema, r"^change_only_test_[0-9a-f]{32}$")
        self.assertEqual(os.environ["SPRING_DATASOURCE_USERNAME"], "atenea")
        self.assertEqual(os.environ["SPRING_DATASOURCE_PASSWORD"], "atenea")
        match = re.fullmatch(r"jdbc:postgresql://(127\.0\.0\.1|db-test):([0-9]+)/atenea_test", os.environ["SPRING_DATASOURCE_URL"])
        self.assertIsNotNone(match)
        os.environ["PGPASSWORD"] = "atenea"
        self.host, self.port = match[1], match[2]
        self.command = ["psql", "-X", "-h", self.host, "-p", self.port,
                        "-U", "atenea", "-d", "atenea_test", "-qAt", "-v", "ON_ERROR_STOP=1"]
        self.db = c.Psql(self.command)
        self.db.execute("SET search_path TO " + c.identifier(self.schema))
        try:
            self.seed()
        except Exception:
            self.db.errors.seek(0)
            print(self.db.errors.read().decode(), file=sys.stderr)
            self.db.close()
            raise
        self.before = self.inspect()
        self.db.execute("BEGIN ISOLATION LEVEL SERIALIZABLE")

    def tearDown(self):
        self.db.close()

    def seed(self):
        q = c.literal
        self.db.execute("""
            INSERT INTO project (id,name,repo_path,default_base_branch) VALUES
              (1,'Synthetic Atenea','/tmp/synthetic-atenea','main'),
              (7,'Synthetic project seven','/tmp/synthetic-seven','main'),
              (9,'Synthetic Beautips','/tmp/synthetic-beautips','main');
            INSERT INTO operator_account (id,email,display_name,password_hash,active,created_at,updated_at)
              VALUES (1,'synthetic@example.invalid','Synthetic','not-a-real-credential',true,now(),now());
            INSERT INTO worker_node (id,protocol_version,endpoint,enabled,healthy,normal_capacity,heavy_capacity,
              normal_in_use,heavy_in_use,capabilities)
              VALUES ('ax42-01','agent-run-worker/v1','http://synthetic.invalid',true,true,2,1,0,0,'project-codex-v4');
        """)
        for cid, key in {**c.TRIAL_CHANGES, 6: c.REAL_CHANGE}.items():
            self.db.execute("INSERT INTO development_change (id,change_key,project_id,title,status,base_ref,base_commit,workspace_branch,workspace_identity,selected_worker_id,project_policy_revision,source_fingerprint_sha256,created_at,updated_at) VALUES ("
                            + f"{cid},{q(key)},1,'Synthetic change',{q('COMPLETED' if cid == 6 else 'ABANDONED')},'refs/heads/main',"
                            + q("1" * 40) + "," + q("atenea/change-" + key) + "," + q("remote:ax42-01:change:" + key)
                            + ",'ax42-01',1," + q("a" * 64) + ",now(),now())")
        for sid, (project, status, remote) in {**c.TRIAL_SESSIONS, 21: (1, "CLOSED", c.REAL_REMOTE)}.items():
            key = c.REAL_CHANGE if sid == 21 else c.TRIAL_CHANGES[4] if sid == 20 else None
            branch = "atenea/change-" + key if key else "atenea/session-" + (remote or str(sid))
            identity = "remote:ax42-01:change:" + key if key else "remote:ax42-01:work-session:" + remote if remote else f"local:work-session:{sid}"
            values = [str(sid), str(project), str(6 if sid == 21 else 4) if key else "NULL", q(status), q("Synthetic session"),
                      "'main'", q(branch), q(identity), "now()", "now()", "now()" if status == "CLOSED" else "NULL",
                      q("REMOTE" if remote else "LOCAL"), "'ax42-01'" if remote else "NULL", q(remote) if remote else "NULL",
                      "'project-codex-v1'" if remote else "NULL", q("RELEASED" if sid == 21 else "UNVERIFIED_LEGACY" if remote and status == "CLOSED" else "NOT_STARTED" if remote else "NOT_REQUIRED")]
            columns = "id,project_id,development_change_id,status,title,base_branch,workspace_branch,workspace_identity,opened_at,last_activity_at,closed_at,execution_target,selected_worker_id,remote_session_id,remote_workload_kind,remote_close_state"
            if sid == 21:
                columns += ",remote_close_operation_id,remote_close_revision,remote_close_receipt_sha256,remote_close_requested_at,remote_close_updated_at,remote_close_released_at"
                values += [q(str(uuid.uuid4())), "2", q("b" * 64), "now()", "now()", "now()"]
            if sid == 6:
                columns += ",draft_fingerprint_sha256,draft_retained_head,draft_staged_change_count,draft_unstaged_change_count,draft_untracked_change_count,draft_blocked_at"
                values += [q("c" * 64), q("d" * 40), "0", "1", "0", "now()"]
            self.db.execute("INSERT INTO work_session (" + columns + ") VALUES (" + ",".join(values) + ")")
        for rid, project in c.TRIAL_RESCUES.items():
            self.db.execute(f"INSERT INTO rescue_session (id,project_id,status,title,opened_at,last_activity_at) VALUES ({rid},{project},'OPEN','Rescate operativo',now(),now()); INSERT INTO rescue_session_turn (rescue_session_id,actor,message_text) VALUES ({rid},'OPERATOR','Synthetic rescue')")
        self.db.execute("""
            INSERT INTO session_turn (id,session_id,actor,message_text) VALUES
              (31,19,'OPERATOR','Synthetic trial'),(32,19,'CODEX','Synthetic trial answer'),
              (41,21,'OPERATOR','Real ticket must remain'),(42,21,'CODEX','Real ticket answer');
            INSERT INTO agent_run (id,session_id,origin_turn_id,result_turn_id,status,target_repo_path,workspace_identity,started_at,finished_at)
              VALUES (101,19,31,32,'FAILED','/tmp/synthetic-trial','local:synthetic:19',now(),now()),
              (103,21,41,42,'FAILED','/tmp/synthetic-real','local:synthetic:21',now(),now());
            INSERT INTO agent_run (id,session_id,origin_turn_id,status,target_repo_path,workspace_identity,started_at,finished_at,retry_of_run_id)
              VALUES (102,19,31,'FAILED','/tmp/synthetic-trial','local:synthetic:19',now(),now(),101),
              (104,21,41,'FAILED','/tmp/synthetic-real','local:synthetic:21',now(),now(),103);
            INSERT INTO api_usage_record (provider,model,feature,status,work_session_id,agent_run_id,started_at)
              VALUES ('synthetic','synthetic','TEST','FAILED',19,101,now()),
              ('synthetic','synthetic','TEST','FAILED',21,103,now());
            INSERT INTO mobile_push_notification_log (event_key,event_type,session_id,title,sent_at,created_at)
              VALUES ('synthetic-trial','TEST',19,'Synthetic trial',now(),now()),
              ('synthetic-real','TEST',21,'Real ticket',now(),now());
            INSERT INTO core_command (id,raw_input,channel,status,target_type,target_id)
              VALUES (51,'Synthetic trial command','TEXT','SUCCEEDED','WORK_SESSION',19),
              (52,'Real ticket command','TEXT','SUCCEEDED','WORK_SESSION',21);
            INSERT INTO core_operator_context (operator_key,active_project_id,active_work_session_id,updated_at)
              VALUES ('synthetic-operator',1,19,now());
        """)
        for cid in [1, 6]:
            op = str(uuid.uuid4())
            self.db.execute("INSERT INTO development_change_operation (operation_id,operator_id,project_id,idempotency_key,operation_kind,request_fingerprint_sha256,target_fingerprint_sha256,state,revision,development_change_id,receipt_sha256,requested_at,completed_at,updated_at) VALUES ("
                            + q(op) + ",1,1," + q(str(uuid.uuid4())) + ",'CREATE'," + q("a" * 64) + "," + q("b" * 64)
                            + f",'SUCCEEDED',1,{cid}," + q("c" * 64) + ",now(),now(),now())")
            audit = str(uuid.uuid4())
            self.db.execute("INSERT INTO v2_audit_event (id,operation_id,project_id,actor_id,capability,event_type,state,revision,request_fingerprint_sha256,target_fingerprint_sha256,occurred_at) VALUES ("
                            + q(audit) + "," + q(op) + ",1,1,'atenea-v2-development-change-control','CREATE','SUCCEEDED',1,"
                            + q("a" * 64) + "," + q("b" * 64) + ",now())")

    def inspect(self):
        return c.inspect_database(self.db, self.schema)

    def plan(self):
        rows, fks, pks, unrelated = self.inspect()
        plan = c.make_plan(rows, fks, pks)
        plan["unrelated"] = unrelated
        return plan

    def test_exact_purge_keeps_real_ticket_and_guards(self):
        plan = self.plan()
        self.assertEqual(17, plan["counts"]["work_session"])
        self.assertEqual(5, plan["counts"]["development_change"])
        self.assertEqual(2, plan["counts"]["rescue_session"])
        self.assertEqual("VERIFIED", c.apply_plan(self.db, plan, self.schema)["state"])
        self.db.execute("COMMIT")
        rows, *_ = self.inspect()
        self.assertEqual([21], [r["id"] for r in rows["work_session"]])
        self.assertEqual([6], [r["id"] for r in rows["development_change"]])
        self.assertEqual([103, 104], sorted(r["id"] for r in rows["agent_run"]))
        self.assertEqual(1, len(rows["v2_audit_event"]))
        self.assertEqual(1, len(rows["development_change_operation"]))
        with self.assertRaises(c.Refused):
            self.db.execute("DELETE FROM development_change_operation")

    def test_idempotent_plan_after_purge(self):
        c.apply_plan(self.db, self.plan(), self.schema)
        plan = self.plan()
        self.assertEqual("ALREADY_CLEAN", plan["state"])
        self.assertEqual({}, plan["counts"])

    def test_rejects_changed_trial_identity(self):
        self.db.execute("UPDATE work_session SET workspace_branch='atenea/foreign' WHERE id=19")
        with self.assertRaisesRegex(c.Refused, "TRIAL_SESSION_IDENTITY_MOVED"):
            self.plan()

    def test_rejects_unreviewed_standalone_session(self):
        self.db.execute("INSERT INTO work_session (id,project_id,status,title,base_branch,workspace_identity,opened_at,last_activity_at,closed_at) VALUES (22,1,'CLOSED','Unknown trial','main','local:synthetic:22',now(),now(),now())")
        with self.assertRaisesRegex(c.Refused, "UNREVIEWED_STANDALONE_SESSION"):
            self.plan()

    def test_rejects_active_execution(self):
        self.db.execute("UPDATE agent_run SET status='RUNNING',finished_at=NULL WHERE id=101")
        with self.assertRaisesRegex(c.Refused, "NON_TERMINAL_EXECUTION"):
            self.plan()

    def test_rejects_cross_owned_retry(self):
        self.db.execute("UPDATE agent_run SET retry_of_run_id=NULL WHERE id=102")
        self.db.execute("UPDATE agent_run SET retry_of_run_id=101 WHERE id=104")
        with self.assertRaisesRegex(c.Refused, "CROSS_OWNED_DEPENDENCY"):
            self.plan()

    def test_rejects_cross_owned_usage_without_fk(self):
        self.db.execute("UPDATE api_usage_record SET agent_run_id=103 WHERE work_session_id=19")
        with self.assertRaisesRegex(c.Refused, "CROSS_OWNED_RUN_DEPENDENCY"):
            self.plan()

    def test_rejects_new_dependent_table(self):
        self.db.execute("CREATE TABLE unexpected_dependency (id bigint PRIMARY KEY, session_id bigint REFERENCES work_session(id))")
        with self.assertRaisesRegex(c.Refused, "UNREVIEWED_DEPENDENT_TABLE"):
            self.plan()

    def test_postcondition_failure_rolls_back_everything(self):
        plan = self.plan()
        plan["protectedSha256"] = "0" * 64
        with self.assertRaisesRegex(c.Refused, "RETAINED_DATA_CHANGED"):
            c.apply_plan(self.db, plan, self.schema)
        self.db.execute("ROLLBACK")
        self.assertEqual(self.before, self.inspect())
        with self.assertRaises(c.Refused):
            self.db.execute("DELETE FROM development_change_operation WHERE development_change_id=1")

    def test_scoped_permission_does_not_delete_real_receipt(self):
        c.install_scoped_delete_permission(self.db, self.plan(), self.schema)
        with self.assertRaises(c.Refused):
            self.db.execute("DELETE FROM development_change_operation WHERE development_change_id=6")

    def test_rejects_disabled_immutability_guard(self):
        self.db.execute("ALTER TABLE development_change_operation DISABLE TRIGGER trg_development_change_operation_terminal_immutable")
        with self.assertRaisesRegex(c.Refused, "IMMUTABILITY_TRIGGER_MOVED"):
            c.apply_plan(self.db, self.plan(), self.schema)

    def test_legacy_consumption_cycle_is_deleted_without_fk_bypass(self):
        q = c.literal
        plan_id, operation_id = str(uuid.uuid4()), str(uuid.uuid4())
        self.db.execute("INSERT INTO remote_close_legacy_plan (plan_id,work_session_id,requested_by,idempotency_key,operation,worker_id,project_identity,remote_session_id,workspace_identity,ownership_fingerprint_sha256,request_fingerprint_sha256,expires_at,created_at) VALUES ("
                        + q(plan_id) + ",19,1," + q(str(uuid.uuid4())) + ",'RECONCILE_REMOTE_CLOSE','ax42-01','atenea',"
                        + q(c.TRIAL_SESSIONS[19][2]) + "," + q("remote:ax42-01:work-session:" + c.TRIAL_SESSIONS[19][2])
                        + "," + q("a" * 64) + "," + q("b" * 64) + ",now()+interval '1 hour',now())")
        self.db.execute("INSERT INTO remote_close_legacy_operation (operation_id,plan_id,work_session_id,requested_by,idempotency_key,operation,ownership_fingerprint_sha256,request_fingerprint_sha256,state,revision,retryable,receipt_sha256,requested_at,updated_at,released_at,created_at) VALUES ("
                        + q(operation_id) + "," + q(plan_id) + ",19,1," + q(str(uuid.uuid4())) + ",'RECONCILE_REMOTE_CLOSE',"
                        + q("a" * 64) + "," + q("b" * 64) + ",'RELEASED',1,false," + q("c" * 64) + ",now(),now(),now(),now())")
        self.db.execute("UPDATE remote_close_legacy_plan SET consumed_at=now(),consumed_by_operation_id=" + q(operation_id)
                        + ",confirmation_idempotency_key=" + q(str(uuid.uuid4())) + " WHERE plan_id=" + q(plan_id))
        result = c.apply_plan(self.db, self.plan(), self.schema)
        self.assertEqual(1, result["counts"]["remote_close_legacy_plan"])
        self.assertEqual(1, result["counts"]["remote_close_legacy_operation"])

    def test_all_four_sealed_journals_remain_protected(self):
        q = c.literal
        for cid, sid in [(4, 20), (6, 21)]:
            self.db.execute("INSERT INTO development_change_workspace_operation (operation_id,operator_id,project_id,development_change_id,idempotency_key,operation_kind,request_fingerprint_sha256,target_fingerprint_sha256,expected_source_revision,expected_source_fingerprint_sha256,expected_canonical_commit,state,revision,result_workspace_state,result_source_state,result_source_revision,result_source_fingerprint_sha256,receipt_sha256,requested_at,dispatched_at,completed_at,updated_at) VALUES ("
                            + q(str(uuid.uuid4())) + f",1,1,{cid}," + q(str(uuid.uuid4())) + ",'INSPECT',"
                            + q("a" * 64) + "," + q("b" * 64) + ",0," + q("c" * 64) + "," + q("d" * 40)
                            + ",'SUCCEEDED',2,'READY','CLEAN',0," + q("e" * 64) + "," + q("f" * 64) + ",now(),now(),now(),now())")
            self.db.execute("INSERT INTO remote_session_operation (operation_id,operator_id,project_id,development_change_id,work_session_id,idempotency_key,operation_kind,expected_change_revision,request_fingerprint_sha256,target_fingerprint_sha256,source_fingerprint_sha256,ownership_fingerprint_sha256,beta_policy_revision,state,revision,resolution,result_change_revision,result_session_state,result_remote_session_id,next_action,receipt_sha256,requested_at,completed_at,updated_at) VALUES ("
                            + q(str(uuid.uuid4())) + f",1,1,{cid},{sid}," + q(str(uuid.uuid4())) + ",'OPEN_OR_RESOLVE_REMOTE_SESSION',0,"
                            + ",".join([q("a" * 64)] * 4) + ",1,'SUCCEEDED',1,'CREATED',0,'CLOSED',"
                            + q(c.REAL_REMOTE if sid == 21 else c.TRIAL_SESSIONS[20][2]) + ",'NONE'," + q("b" * 64) + ",now(),now(),now())")
        c.apply_plan(self.db, self.plan(), self.schema)
        rows, *_ = self.inspect()
        for table in c.GUARD_TABLES.values():
            self.assertEqual(1, len(rows[table]))
        c.verify_guards(self.db, self.schema)

    def test_rejects_client_paths_and_commands(self):
        for option in ["--path=/tmp/anything", "--session=21", "--command=anything", "--version=1"]:
            result = subprocess.run(["/usr/bin/python3", str(Path(c.__file__)), "--plan", option],
                                    capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
        for option in ["--ap", "--pl"]:
            result = subprocess.run(["/usr/bin/python3", str(Path(c.__file__)), option], capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
        self.assertEqual(self.before, self.inspect())

    def test_backup_failure_prevents_all_mutation(self):
        plan = self.plan()
        with patch.object(c, "snapshot_backup", side_effect=c.Refused("BACKUP_FAILED")), patch.object(c, "apply_plan") as apply:
            with self.assertRaisesRegex(c.Refused, "BACKUP_FAILED"):
                c.execute_verified_purge(self.db, plan, self.schema)
            apply.assert_not_called()
        self.assertEqual(self.before, self.inspect())

    def test_exact_numeric_retained_values_cannot_drift(self):
        self.db.execute("UPDATE api_usage_record SET estimated_cost=5000000000.00000001 WHERE work_session_id=21")
        plan = self.plan()
        plan["exactRetained"] = c.retained_hashes(self.db, plan, self.schema)
        self.db.execute("UPDATE api_usage_record SET estimated_cost=5000000000.00000002 WHERE work_session_id=21")
        with self.assertRaisesRegex(c.Refused, "RETAINED_DATA_MOVED"):
            c.apply_plan(self.db, plan, self.schema)
        self.assertEqual(18, len(self.inspect()[0]["work_session"]))

    def commit_evidence_fixture(self, directory):
        # Synthetic receipt fixture, never an operator/PROD backup. The real
        # pg_dump/pg_restore snapshot contract is covered separately below.
        archive = Path(directory)
        data = b"synthetic validated-backup receipt fixture"
        (archive / "atenea_prod.dump").write_bytes(data)
        plan = {"operation": c.OPERATION, "state": "READY", "protectedSha256": "a" * 64,
                "exactRetained": {"work_session": "b" * 64}, "unrelated": {}, "counts": {"work_session": 17}}
        verified = {"state": "VERIFIED", "protectedSha256": "a" * 64, "counts": {"work_session": 17}}
        backup = {"structure": "PASS", "sha256": c.hashlib.sha256(data).hexdigest(), "size": len(data)}
        for name, value in [("plan.json", plan), ("verified-before-commit.json", verified), ("backup.json", backup)]:
            c.write_private(archive / name, value)
        return archive, {**plan, "state": "ALREADY_CLEAN", "counts": {}}

    def test_lost_commit_acknowledgement_is_reconciled_without_second_purge(self):
        with tempfile.TemporaryDirectory(prefix="change-only-commit-test-") as directory:
            archive, plan = self.commit_evidence_fixture(directory)
            with patch.object(c, "ARCHIVE", archive), patch.object(c, "authority"):
                self.assertEqual("COMMIT_ACKNOWLEDGEMENT_RECONCILED", c.reconcile_commit_evidence(plan))
                self.assertEqual("ALREADY_CLEAN", c.reconcile_commit_evidence(plan))
        self.assertEqual(self.before, self.inspect())

    def test_commit_reconciliation_rejects_changed_backup(self):
        with tempfile.TemporaryDirectory(prefix="change-only-commit-test-") as directory:
            archive, plan = self.commit_evidence_fixture(directory)
            (archive / "atenea_prod.dump").write_bytes(b"unexpected bytes")
            with patch.object(c, "ARCHIVE", archive), patch.object(c, "authority"):
                with self.assertRaisesRegex(c.Refused, "REPLAY_BACKUP_MISMATCH"):
                    c.reconcile_commit_evidence(plan)
            self.assertFalse((archive / "committed.json").exists())
        self.assertEqual(self.before, self.inspect())

    def test_snapshot_backup_is_structurally_readable_in_pg16(self):
        snapshot = self.db.query("SELECT to_json(pg_export_snapshot())")
        with tempfile.TemporaryDirectory(prefix="change-only-backup-test-") as directory:
            dump = Path(directory) / "synthetic.dump"
            with dump.open("wb") as out:
                subprocess.run(["pg_dump", "-h", self.host, "-p", self.port, "-U", "atenea", "-d", "atenea_test",
                                "--format=custom", "--schema=" + self.schema, "--snapshot=" + snapshot], stdout=out, check=True)
            toc = subprocess.check_output(["pg_restore", "--list", str(dump)], text=True)
            self.assertIn("work_session", toc)
            self.assertGreater(dump.stat().st_size, 0)
        self.assertEqual(self.before, self.inspect())


if __name__ == "__main__":
    unittest.main()
