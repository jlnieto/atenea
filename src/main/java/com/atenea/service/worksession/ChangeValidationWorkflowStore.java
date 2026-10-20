package com.atenea.service.worksession;

import com.atenea.api.worksession.ChangeValidationWorkflowResponse;
import com.atenea.persistence.worksession.WorkSessionEntity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChangeValidationWorkflowStore {
    private final JdbcTemplate jdbc;
    public ChangeValidationWorkflowStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Workflow(UUID id, Long sessionId, UUID changeKey, Long projectId, long revision, String fingerprint,
            UUID remoteSessionId, String workerId, String workspaceIdentity, String workspaceBranch,
            String canonicalCommit, String profileRevision, String state, String phase, UUID activeOperationId,
            UUID retryOfOperationId, boolean retryConsumed, String errorCode,
            Instant createdAt, Instant updatedAt, Instant finishedAt) {
        public boolean terminal() { return !"RUNNING".equals(state); }
        public ChangeValidationWorkflowResponse response() {
            return new ChangeValidationWorkflowResponse(id,sessionId,revision,fingerprint,state,phase,
                    activeOperationId,errorCode,createdAt,updatedAt,finishedAt);
        }
    }

    public Optional<Workflow> find(UUID id) {
        return jdbc.query("SELECT * FROM change_validation_workflow WHERE id=?",ChangeValidationWorkflowStore::row,id)
                .stream().findFirst();
    }
    public Workflow locked(UUID id) {
        return jdbc.query("SELECT * FROM change_validation_workflow WHERE id=? FOR UPDATE",ChangeValidationWorkflowStore::row,id)
                .stream().findFirst().orElseThrow(() -> new WorkSessionOperationBlockedException("Validation workflow not found"));
    }
    public Optional<Workflow> active(Long sessionId) {
        return jdbc.query("SELECT * FROM change_validation_workflow WHERE session_id=? AND state='RUNNING'",
                ChangeValidationWorkflowStore::row,sessionId).stream().findFirst();
    }
    public Optional<Workflow> latestSession(Long sessionId) {
        return jdbc.query("SELECT * FROM change_validation_workflow WHERE session_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                ChangeValidationWorkflowStore::row,sessionId).stream().findFirst();
    }
    public static Optional<Workflow> latest(JdbcTemplate jdbc, Long sessionId, long revision, String fingerprint) {
        return jdbc.query("""
                SELECT * FROM change_validation_workflow WHERE session_id=? AND source_revision=?
                    AND source_fingerprint_sha256=? ORDER BY created_at DESC,id DESC LIMIT 1
                """,ChangeValidationWorkflowStore::row,sessionId,revision,fingerprint).stream().findFirst();
    }
    public Workflow create(UUID id, WorkSessionEntity s, UUID retryOf) {
        var c=s.getDevelopmentChange();
        jdbc.update("""
            INSERT INTO change_validation_workflow(id,session_id,change_key,project_id,source_revision,source_fingerprint_sha256,
                remote_session_id,worker_id,workspace_identity,workspace_branch,canonical_commit,state,phase,retry_of_operation_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,'RUNNING','QUEUED',?)
            """,id,s.getId(),c.getChangeKey(),s.getProject().getId(),c.getSourceRevision(),c.getSourceFingerprintSha256(),s.getRemoteSessionId(),
                s.getSelectedWorkerId(),s.getWorkspaceIdentity(),s.getWorkspaceBranch(),s.getCanonicalSourceCommit(),retryOf);
        return locked(id);
    }
    public void progress(Workflow w, String phase, UUID operationId, boolean retryConsumed, String errorCode) {
        jdbc.update("""
                UPDATE change_validation_workflow SET phase=?,active_operation_id=?,retry_consumed=?,error_code=?,updated_at=now()
                WHERE id=? AND state='RUNNING'
                """,phase,operationId,retryConsumed,errorCode,w.id());
    }
    public void finish(Workflow w, String state, String errorCode) {
        jdbc.update("""
                UPDATE change_validation_workflow SET state=?,phase='FINISHED',error_code=?,finished_at=now(),updated_at=now()
                WHERE id=? AND state='RUNNING'
                """,state,errorCode,w.id());
    }
    public List<UUID> pending() {
        return jdbc.query("SELECT id FROM change_validation_workflow WHERE state='RUNNING' ORDER BY updated_at,id LIMIT 10",
                (rs,n)->rs.getObject("id",UUID.class));
    }
    private static Workflow row(ResultSet r,int n) throws SQLException {
        return new Workflow(r.getObject("id",UUID.class),r.getLong("session_id"),r.getObject("change_key",UUID.class),r.getLong("project_id"),
                r.getLong("source_revision"),r.getString("source_fingerprint_sha256"),r.getObject("remote_session_id",UUID.class),
                r.getString("worker_id"),r.getString("workspace_identity"),r.getString("workspace_branch"),r.getString("canonical_commit"),
                r.getString("profile_revision"),r.getString("state"),r.getString("phase"),r.getObject("active_operation_id",UUID.class),
                r.getObject("retry_of_operation_id",UUID.class),r.getBoolean("retry_consumed"),r.getString("error_code"),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),
                r.getTimestamp("finished_at")==null?null:r.getTimestamp("finished_at").toInstant());
    }
}
