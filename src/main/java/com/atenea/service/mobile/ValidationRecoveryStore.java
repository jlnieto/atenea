package com.atenea.service.mobile;

import com.atenea.delivery.DeliveryRejectedException;
import com.atenea.remoteworker.ValidationRecoveryCommand;
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
public class ValidationRecoveryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ValidationRecoveryStore(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public void admissionLock() { jdbc.execute("SELECT pg_advisory_xact_lock(814205002)"); }
    public boolean idle() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT EXISTS(SELECT 1 FROM agent_run WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED'))
             AND NOT EXISTS(SELECT 1 FROM validation_operation WHERE status='RUNNING')
             AND NOT EXISTS(SELECT 1 FROM mobile_delivery_operation WHERE kind='RELEASE'
                 AND state NOT IN ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED'))
            """,Boolean.class));
    }
    public List<Entry> list(Long session) {
        return jdbc.query("SELECT * FROM mobile_validation_recovery WHERE session_id=? ORDER BY created_at DESC",this::row,session);
    }
    public Entry get(UUID id,boolean lock) {
        return jdbc.query("SELECT * FROM mobile_validation_recovery WHERE id=?"+(lock?" FOR UPDATE":""),this::row,id)
                .stream().findFirst().orElseThrow(() -> new DeliveryRejectedException("VALIDATION_RECOVERY_NOT_FOUND"));
    }
    public Entry create(UUID id,Long session,Long operator,UUID validation,String kind,long revision,
            String fingerprint,ValidationRecoveryCommand command) {
        jdbc.update("""
            INSERT INTO mobile_validation_recovery(id,session_id,operator_id,validation_id,kind,state,
                source_revision,source_fingerprint_sha256,command_json)
            VALUES(?,?,?,?,?,'PREPARE_REQUESTED',?,?,?::jsonb)
            ON CONFLICT(session_id,validation_id,kind) DO NOTHING
            """,id,session,operator,validation,kind,revision,fingerprint,command==null?null:json(command));
        return list(session).stream().filter(e->e.validationId().equals(validation)&&e.kind().equals(kind))
                .findFirst().orElseThrow();
    }
    public void state(UUID id,String state,String error) {
        jdbc.update("UPDATE mobile_validation_recovery SET state=?,error_code=?,updated_at=now() WHERE id=?",state,error,id);
    }
    public void proof(UUID id,String state,JsonNode evidence,String fingerprint) {
        jdbc.update("UPDATE mobile_validation_recovery SET state=?,evidence_json=?::jsonb,result_fingerprint_sha256=?,error_code=NULL,updated_at=now() WHERE id=?",
                state,json(evidence),fingerprint,id);
    }
    public void investigated(UUID id,Long run) {
        jdbc.update("UPDATE mobile_validation_recovery SET state='INVESTIGATED',run_id=?,error_code=NULL,updated_at=now() WHERE id=?",run,id);
    }
    public List<UUID> pending() {
        return jdbc.queryForList("""
            SELECT id FROM mobile_validation_recovery WHERE state IN
             ('PREPARE_REQUESTED','PREPARING','APPLY_REQUESTED','APPLYING') ORDER BY created_at LIMIT 4
            """,UUID.class);
    }
    public List<Entry> queuedInvestigations() {
        return jdbc.query("""
            SELECT e.* FROM mobile_validation_recovery e
             JOIN agent_run r ON r.id=e.run_id AND r.session_id=e.session_id
            WHERE e.kind='INVESTIGATION' AND e.state='INVESTIGATED'
              AND r.execution_target='REMOTE' AND r.status='QUEUED'
            ORDER BY e.created_at LIMIT 4
            """,this::row);
    }
    private Entry row(ResultSet rs,int ignored) throws SQLException {
        try {
            String command=rs.getString("command_json");
            return new Entry(rs.getObject("id",UUID.class),rs.getLong("session_id"),rs.getLong("operator_id"),
                    rs.getObject("validation_id",UUID.class),rs.getString("kind"),rs.getString("state"),
                    rs.getLong("source_revision"),rs.getString("source_fingerprint_sha256"),
                    command==null?null:mapper.readValue(command,ValidationRecoveryCommand.class),
                    mapper.readTree(rs.getString("evidence_json")),rs.getString("result_fingerprint_sha256"),
                    rs.getObject("run_id",Long.class),rs.getString("error_code"),rs.getTimestamp("created_at").toInstant());
        } catch (java.io.IOException invalid) { throw new SQLException("Invalid durable validation repair",invalid); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Cannot persist validation repair",invalid); }
    }
    public record Entry(UUID id,Long sessionId,Long operatorId,UUID validationId,String kind,String state,
            long sourceRevision,String sourceFingerprint,ValidationRecoveryCommand command,JsonNode evidence,
            String resultFingerprint,Long runId,String errorCode,Instant createdAt) {
        public View view() { return view(null); }
        public View view(String runStatus) { return new View(id,kind,state,sourceRevision,runId,errorCode,runStatus); }
    }
    public record View(UUID id,String kind,String state,long sourceRevision,Long runId,String errorCode,String runStatus) {
        public View(UUID id,String kind,String state,long sourceRevision,Long runId,String errorCode) {
            this(id,kind,state,sourceRevision,runId,errorCode,null);
        }
        public String getProtocolVersion() { return "validation-recovery/v1"; }
    }
}
