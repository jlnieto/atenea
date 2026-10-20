package com.atenea.delivery;

import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Immutable owner binding and actor authority for the independent retirement journal. */
@Component
public class WorkSessionRetirementStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OperatorRepository operators;
    public WorkSessionRetirementStore(JdbcTemplate jdbc, ObjectMapper mapper, OperatorRepository operators) {
        this.jdbc=jdbc; this.mapper=mapper; this.operators=operators;
    }
    public record Operation(UUID operationId, Long sessionId, Long operatorId, UUID idempotencyKey,
            String state, JsonNode owner, JsonNode evidence, String errorCode) {}
    public Optional<Operation> find(Long sessionId) {
        return jdbc.query("SELECT * FROM work_session_retirement WHERE session_id=?", (rs, index) -> {
            try { return new Operation(rs.getObject("operation_id",UUID.class), rs.getLong("session_id"),
                rs.getLong("operator_id"),rs.getObject("idempotency_key",UUID.class),rs.getString("state"),
                mapper.readTree(rs.getString("owner_json")),mapper.readTree(rs.getString("evidence_json")),rs.getString("error_code")); }
            catch (java.io.IOException invalid) { throw new DeliveryRejectedException("RETIREMENT_EVIDENCE_INVALID"); }
        },sessionId).stream().findFirst();
    }
    public void administrator(Long id) {
        operators.findById(id).filter(o -> o.isActive() && o.getCodexOperationsRole()==CodexOperationsRole.PLATFORM_ADMINISTRATOR)
            .orElseThrow(() -> new DeliveryRejectedException("PLATFORM_ADMINISTRATOR_REQUIRED"));
    }
    public ObjectNode owner(WorkSessionEntity session) {
        if (!ProjectCodexIdentity.matches(session) || session.getExecutionTarget()!=ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.WORKER_ID.equals(session.getSelectedWorkerId())
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                || session.getRemoteSessionId()==null || session.getId()==null) {
            throw new DeliveryRejectedException("RETIREMENT_EXACT_OWNER_REQUIRED");
        }
        var change=session.getDevelopmentChange();
        String identity="remote:ax42-01:"+(change==null ? "work-session:"+session.getRemoteSessionId() : "change:"+change.getChangeKey());
        String branch="atenea/"+(change==null ? "session-"+session.getRemoteSessionId() : "change-"+change.getChangeKey());
        if (!identity.equals(session.getWorkspaceIdentity()) || !branch.equals(session.getWorkspaceBranch())
                || (change==null && !ProjectCodexIdentity.hasCanonicalSourceObservation(session))
                || (change!=null && (!Objects.equals(session.getProject().getId(),change.getProject().getId())
                    || !identity.equals(change.getWorkspaceIdentity()) || !branch.equals(change.getWorkspaceBranch())
                    || !session.getSelectedWorkerId().equals(change.getSelectedWorkerId())
                    || !"refs/heads/main".equals(change.getBaseRef())
                    || change.getBaseCommit()==null || !change.getBaseCommit().matches("[0-9a-f]{40}")))) {
            throw new DeliveryRejectedException("RETIREMENT_EXACT_OWNER_REQUIRED");
        }
        ObjectNode value=mapper.createObjectNode();
        value.put("schemaVersion", "work-session-retirement-owner-v1");
        // Identifier strings remain type-stable across Jackson and PostgreSQL
        // JSONB (small integral numbers otherwise become IntNode on reload).
        value.put("sessionId",session.getId().toString()); value.put("projectId",session.getProject().getId().toString());
        value.put("remoteSessionId",session.getRemoteSessionId().toString()); value.put("workerId",session.getSelectedWorkerId());
        value.put("workspaceIdentity",identity); value.put("workspaceBranch",branch);
        value.put("canonicalCommit",session.getCanonicalSourceCommit());
        value.put("finalCommit",session.getFinalCommitSha());
        value.put("changeKey",change==null ? null : change.getChangeKey().toString());
        value.put("baseCommit",change==null ? null : change.getBaseCommit());
        return value;
    }
    public void requireBound(Operation op, WorkSessionEntity session) {
        administrator(op.operatorId());
        if (!op.sessionId().equals(session.getId()) || !op.owner().equals(owner(session)))
            throw new DeliveryRejectedException("RETIREMENT_OWNER_MOVED");
        if (session.getDevelopmentChange()!=null
                && !List.of(com.atenea.persistence.developmentchange.DevelopmentChangeStatus.OPEN,
                    com.atenea.persistence.developmentchange.DevelopmentChangeStatus.PAUSED)
                    .contains(session.getDevelopmentChange().getStatus()))
            throw new DeliveryRejectedException("RETIREMENT_CHANGE_STATE_MISMATCH");
    }
    public boolean authorizeClose(WorkSessionEntity session) {
        var retained=find(session.getId());
        if (retained.isEmpty()) return false;
        var op=retained.get(); requireBound(op,session);
        if (!"APPLYING".equals(op.state())) throw new DeliveryRejectedException("RETIREMENT_NOT_APPLYING");
        if (session.getRemoteCloseOperationId()!=null && !op.operationId().equals(session.getRemoteCloseOperationId()))
            throw new DeliveryRejectedException("RETIREMENT_CLOSE_ID_MISMATCH");
        requireIdle(session.getId());
        return true;
    }
    public void requireIdle(Long sessionId) {
        jdbc.execute("SELECT pg_advisory_xact_lock(814205002)");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT EXISTS (SELECT 1 FROM agent_run WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED'))
                AND NOT EXISTS (SELECT 1 FROM validation_operation WHERE status='RUNNING')
                AND NOT EXISTS (SELECT 1 FROM change_validation_workflow WHERE state='RUNNING')
                AND NOT EXISTS (SELECT 1 FROM mobile_source_update_operation WHERE session_id=?
                    AND state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE','RETRY_REQUESTED','RESOLVING'))
                AND NOT EXISTS (SELECT 1 FROM mobile_source_finalization WHERE session_id=? AND state<>'PUBLISHED')
                AND NOT EXISTS (SELECT 1 FROM mobile_delivery_operation WHERE session_id=?
                    AND (kind='FINALIZE' OR state NOT IN ('SUCCEEDED','FAILED','BLOCKED','ROLLED_BACK')))
                AND NOT EXISTS (SELECT 1 FROM mobile_delivery_operation WHERE kind='RELEASE'
                    AND state IN ('CONFIRMED','ACCEPTED','APPLYING','ROLLING_BACK','QUARANTINED','ROLLBACK_FAILED'))
            """, Boolean.class,sessionId,sessionId,sessionId)))
            throw new DeliveryRejectedException("RETIREMENT_WORK_IN_PROGRESS");
    }
    public Operation create(Long sessionId, Long actor, UUID key, JsonNode owner) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM work_session_retirement WHERE operator_id=? AND idempotency_key=?)",
                Boolean.class,actor,key))) throw new DeliveryRejectedException("RETIREMENT_IDEMPOTENCY_MISMATCH");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO work_session_retirement(operation_id,session_id,operator_id,idempotency_key,state,owner_json,created_at,updated_at)
                VALUES (?,?,?,?,'APPLYING',?::jsonb,now(),now())
            """,id,sessionId,actor,key,owner.toString());
        return find(sessionId).orElseThrow();
    }
    public void update(Operation op,String state,String error,JsonNode evidence) {
        jdbc.update("""
            UPDATE work_session_retirement SET state=?,error_code=?,evidence_json=?::jsonb,updated_at=now(),
                finished_at=CASE WHEN ?='SUCCEEDED' THEN now() ELSE NULL END WHERE operation_id=?
            """,state,error,evidence.toString(),state,op.operationId());
    }
    public List<Long> pending() {
        return jdbc.queryForList("SELECT session_id FROM work_session_retirement WHERE state='APPLYING' ORDER BY created_at",Long.class);
    }
    public Optional<Operation> forChange(UUID key) {
        return jdbc.queryForList("""
            SELECT r.session_id FROM work_session_retirement r JOIN work_session ws ON ws.id=r.session_id
                JOIN development_change dc ON dc.id=ws.development_change_id WHERE dc.change_key=?
            """,Long.class,key).stream().findFirst().flatMap(this::find);
    }
    public Long archivedSession(UUID changeKey) {
        return jdbc.query("""
            SELECT ws.id FROM work_session ws JOIN development_change dc ON dc.id=ws.development_change_id
                JOIN work_session_retirement r ON r.session_id=ws.id
            WHERE dc.change_key=? AND dc.status='ABANDONED' AND ws.status='CLOSED'
                AND ws.remote_close_state='RELEASED' AND r.state='SUCCEEDED'
                AND r.operation_id=ws.remote_close_operation_id
                AND r.evidence_json->>'receiptSha256'=ws.remote_close_receipt_sha256
            """,(rs,index)->rs.getLong("id"),changeKey).stream().findFirst().orElse(null);
    }
}
