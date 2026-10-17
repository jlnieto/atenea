package com.atenea.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public DeliveryStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public List<DeliveryOperation> list(Long sessionId) {
        return jdbc.query("SELECT * FROM mobile_delivery_operation WHERE session_id=? ORDER BY created_at DESC LIMIT 30", this::row, sessionId);
    }
    public DeliveryOperation get(UUID id, boolean lock) {
        // IDs never change. This still serializes state writers while allowing
        // a REQUIRES_NEW publication intent to retain an FK to this operation.
        var rows = jdbc.query("SELECT * FROM mobile_delivery_operation WHERE id=?" + (lock ? " FOR NO KEY UPDATE" : ""), this::row, id);
        if (rows.size() != 1) throw new DeliveryRejectedException("OPERATION_NOT_FOUND");
        return rows.getFirst();
    }
    public java.util.Optional<DeliveryOperation> find(UUID id) {
        return jdbc.query("SELECT * FROM mobile_delivery_operation WHERE id=?", this::row, id).stream().findFirst();
    }
    public void lockReleaseIntent() { jdbc.execute("SELECT pg_advisory_xact_lock(814205001)"); }
    public void lockAdmissionAndRequireIdle() {
        jdbc.execute("SELECT pg_advisory_xact_lock(814205002)");
        Long active = jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",Long.class);
        if (active == null || active != 0L) throw new DeliveryRejectedException("ACTIVE_AGENT_RUN");
        Long preparation = jdbc.queryForObject("""
                SELECT count(*) FROM mobile_source_update_operation
                WHERE state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE','RETRY_REQUESTED','RESOLVING')
                """, Long.class);
        if (preparation == null || preparation != 0L) throw new DeliveryRejectedException("SOURCE_UPDATE_IN_PROGRESS");
        Long finalization = jdbc.queryForObject("SELECT count(*) FROM mobile_source_finalization WHERE state <> 'PUBLISHED'",Long.class);
        if (finalization == null || finalization != 0L) throw new DeliveryRejectedException("SOURCE_FINALIZATION_IN_PROGRESS");
    }
    public java.util.Optional<DeliveryOperation> activeRelease() {
        return jdbc.query("SELECT * FROM mobile_delivery_operation WHERE kind IN ('RELEASE','ANDROID_VERSION') "
                + "AND state NOT IN ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED') LIMIT 1", this::row).stream().findFirst();
    }
    public java.util.Optional<DeliveryOperation> integrated(Long sessionId, String head) {
        return jdbc.query("SELECT * FROM mobile_delivery_operation WHERE session_id=? AND kind='INTEGRATE' "
                + "AND state='SUCCEEDED' AND source_commit=? ORDER BY created_at DESC LIMIT 1", this::row, sessionId, head).stream().findFirst();
    }
    public java.util.Optional<DeliveryOperation> completion(Long sessionId) {
        return jdbc.query("SELECT * FROM mobile_delivery_operation WHERE session_id=? AND kind='FINALIZE'",
                this::row, sessionId).stream().findFirst();
    }
    public Long completedSession(UUID changeKey) {
        return jdbc.query("""
                SELECT op.session_id FROM mobile_delivery_operation op JOIN work_session ws ON ws.id=op.session_id
                JOIN development_change dc ON dc.id=ws.development_change_id
                WHERE op.kind='FINALIZE' AND op.state='SUCCEEDED' AND ws.status='CLOSED'
                  AND ws.remote_close_state='RELEASED' AND dc.status='COMPLETED' AND dc.change_key=?
                  AND op.evidence_json->'completion'->>'changeKey'=?
                """, (rs, index) -> rs.getLong("session_id"), changeKey, changeKey.toString()).stream().findFirst().orElse(null);
    }
    public void lockCompletionAdmissionAndRequireIdle(Long sessionId) {
        jdbc.execute("SELECT pg_advisory_xact_lock(814205002)");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS (SELECT 1 FROM agent_run WHERE session_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED'))
                  AND NOT EXISTS (SELECT 1 FROM validation_operation WHERE work_session_id=? AND status='RUNNING')
                  AND NOT EXISTS (SELECT 1 FROM mobile_source_update_operation WHERE session_id=?
                    AND state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE','RETRY_REQUESTED','RESOLVING'))
                  AND NOT EXISTS (SELECT 1 FROM mobile_source_finalization WHERE session_id=? AND state <> 'PUBLISHED')
                  AND NOT EXISTS (SELECT 1 FROM mobile_delivery_operation WHERE session_id=? AND kind <> 'FINALIZE'
                    AND state NOT IN ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED','ROLLBACK_FAILED'))
                  AND NOT EXISTS (SELECT 1 FROM mobile_delivery_operation WHERE kind='RELEASE'
                    AND state IN ('CONFIRMED','ACCEPTED','APPLYING','ROLLING_BACK','QUARANTINED','ROLLBACK_FAILED'))
                """, Boolean.class, sessionId, sessionId, sessionId, sessionId, sessionId))) {
            throw new DeliveryRejectedException("COMPLETION_WORK_IN_PROGRESS");
        }
    }
    /** Closing/reconciling a change consumes its already-authorized completion. */
    public void requireCompletionClose(com.atenea.persistence.worksession.WorkSessionEntity session) {
        DeliveryOperation operation = completion(session.getId())
                .orElseThrow(() -> new DeliveryRejectedException("DELIVERED_CHANGE_COMPLETION_REQUIRED"));
        ChangeCompletionEvidence binding = ChangeCompletionEvidence.require(operation, session, mapper);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM operator_account "
                + "WHERE id=? AND active AND codex_operations_role='PLATFORM_ADMINISTRATOR')", Boolean.class, operation.operatorId()))) {
            throw new DeliveryRejectedException("PLATFORM_ADMINISTRATOR_REQUIRED");
        }
        JsonNode observed = operation.evidence().path("workspaceInspection");
        JsonNode delivered = operation.evidence().path("requiredDeliveryBeforeClose");
        long now = Instant.now().getEpochSecond();
        if (!"APPLYING".equals(operation.state()) || !"OWNED".equals(observed.path("disposition").asText())
                || session.getDevelopmentChange().getStatus() != com.atenea.persistence.developmentchange.DevelopmentChangeStatus.OPEN
                || session.getDevelopmentChange().getValidationState() != com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState.CURRENT
                || session.getDevelopmentChange().getWorkspaceState() != com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState.READY
                || session.getDevelopmentChange().getSourceState() == com.atenea.persistence.developmentchange.DevelopmentChangeSourceState.STALE
                || session.getDevelopmentChange().getSourceState() == com.atenea.persistence.developmentchange.DevelopmentChangeSourceState.BLOCKED
                || session.getAcceptanceState() != com.atenea.persistence.worksession.WorkSessionAcceptanceState.INTEGRATION_READY
                || session.getPullRequestStatus() != com.atenea.persistence.worksession.WorkSessionPullRequestStatus.MERGED
                || !com.atenea.remoteworker.ProjectCodexIdentity.matches(session)
                || !"ax42-01".equals(session.getSelectedWorkerId())
                || !session.getSelectedWorkerId().equals(session.getDevelopmentChange().getSelectedWorkerId())
                || !session.getWorkspaceBranch().equals("atenea/change-" + binding.changeKey())
                || !session.getWorkspaceIdentity().equals("remote:ax42-01:change:" + binding.changeKey())
                || !ChangeDeliveryReadiness.verifiedForClose(delivered,session.getId(),binding.publishedHeadCommit(),binding.integratedMergeCommit())
                || observed.path("workspaceDirty").asBoolean(true) || observed.path("retainedDraft").asBoolean(true)
                || !session.getFinalCommitSha().equals(observed.path("sourceCommit").asText())
                || !observed.path("requestFingerprintSha256").asText().matches("[0-9a-f]{64}")) {
            throw new DeliveryRejectedException("COMPLETION_WORKSPACE_NOT_VERIFIED");
        }
        lockCompletionAdmissionAndRequireIdle(session.getId());
    }
    public java.util.Optional<DeliveryOperation> completedRecovery(Long sessionId, Long actor, UUID integration, String source) {
        return jdbc.query("SELECT * FROM mobile_delivery_operation WHERE session_id=? AND operator_id=? "
                + "AND kind='RELEASE' AND target='APP_PROD' AND state='SUCCEEDED' AND source_commit=? "
                + "AND evidence_json->'releaseRecovery'->>'integrationOperationId'=? ORDER BY created_at DESC LIMIT 1",
                this::row, sessionId, actor, source, integration.toString()).stream().findFirst();
    }
    public java.util.Optional<DeliveryOperation> ownedHeadUfdRequest(Long sessionId, String head, String branch) {
        // Search the complete durable history, not the UI's last 30 operations.
        // A new publication intent must not resend a previously claimed head.
        return jdbc.query("SELECT * FROM mobile_delivery_operation WHERE session_id=? AND kind='PUBLISH_PR' "
                + "AND evidence_json->'ufdDispatch'->>'headSha'=? "
                + "AND evidence_json->'ufdDispatch'->>'headBranch'=? ORDER BY created_at LIMIT 1",
                this::row, sessionId, head, branch).stream().findFirst();
    }
    public DeliveryOperation create(Long sessionId, Long actor, String kind, DeliveryTarget target,
            String source, String state, JsonNode evidence) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mobile_delivery_operation
                (id,session_id,operator_id,kind,target,source_commit,execution_id,state,evidence_json,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,now(),now())
                """, id, sessionId, actor, kind, target.name(), source, UUID.randomUUID(), state, evidence.toString());
        return get(id, false);
    }
    public void update(DeliveryOperation op, String state, String code, String planSha, JsonNode evidence) {
        if ("FINALIZE".equals(op.kind()) && (!op.evidence().path(ChangeCompletionEvidence.FIELD)
                .equals(evidence.path(ChangeCompletionEvidence.FIELD)) || !op.evidence().path("completionBindingSha256")
                .equals(evidence.path("completionBindingSha256")))) {
            throw new DeliveryRejectedException("COMPLETION_EVIDENCE_MISMATCH");
        }
        JsonNode retained = retainRecoverySource(op, evidence);
        jdbc.update("""
                UPDATE mobile_delivery_operation SET state=?,error_code=?,plan_sha256=?,evidence_json=?::jsonb,
                  updated_at=now(),finished_at=CASE WHEN ? THEN now() ELSE NULL END WHERE id=?
                """, state, code, planSha, retained.toString(), DeliveryOperation.TERMINAL.contains(state), op.id());
    }
    static JsonNode retainRecoverySource(DeliveryOperation op, JsonNode observed) {
        if (!observed.isObject()) throw new DeliveryRejectedException("RELEASE_EVIDENCE_MISMATCH");
        if (observed.has(ReleaseRecoverySource.FIELD)
                && !observed.path(ReleaseRecoverySource.FIELD).equals(op.evidence().path(ReleaseRecoverySource.FIELD))) {
            throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
        }
        if (ReleaseRecoverySource.from(op) == null) return observed;
        com.fasterxml.jackson.databind.node.ObjectNode retained = observed.deepCopy();
        retained.set(ReleaseRecoverySource.FIELD, op.evidence().path(ReleaseRecoverySource.FIELD).deepCopy());
        return retained;
    }
    public List<UUID> pending() {
        return jdbc.query("""
                SELECT id FROM mobile_delivery_operation
                WHERE kind <> 'FINALIZE' AND state NOT IN ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED','ROLLBACK_FAILED')
                  AND (state <> 'READY' OR (evidence_json->>'expiresAt')::bigint <= extract(epoch from now()))
                ORDER BY updated_at LIMIT 10
                """, (rs, index) -> rs.getObject("id", UUID.class));
    }
    public List<UUID> pendingCompletions() {
        return jdbc.query("SELECT id FROM mobile_delivery_operation WHERE kind='FINALIZE' AND state IN ('QUEUED','APPLYING') "
                + "ORDER BY updated_at LIMIT 10", (rs, index) -> rs.getObject("id", UUID.class));
    }
    private DeliveryOperation row(ResultSet rs, int index) throws SQLException {
        try {
            return new DeliveryOperation(rs.getObject("id", UUID.class), rs.getLong("session_id"), rs.getLong("operator_id"),
                    rs.getString("kind"), DeliveryTarget.valueOf(rs.getString("target")), rs.getString("source_commit"),
                    rs.getObject("execution_id", UUID.class), rs.getString("state"), rs.getString("plan_sha256"),
                    mapper.readTree(rs.getString("evidence_json")), rs.getString("error_code"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        } catch (java.io.IOException exception) { throw new SQLException("Delivery evidence invalid", exception); }
    }
}
