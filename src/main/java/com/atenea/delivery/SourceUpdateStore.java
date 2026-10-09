package com.atenea.delivery;

import com.atenea.remoteworker.DevelopmentChangeSourceUpdateCommand;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateGateway.Preparation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SourceUpdateStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public SourceUpdateStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public Optional<SourceUpdateOperation> latest(Long sessionId) {
        return jdbc.query("SELECT * FROM mobile_source_update_operation WHERE session_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                this::row, sessionId).stream().findFirst();
    }
    public SourceUpdateOperation get(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM mobile_source_update_operation WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                this::row, id).stream().findFirst().orElseThrow(() -> new DeliveryRejectedException("SOURCE_UPDATE_NOT_FOUND"));
    }
    public void lockSessionBeforeReadingSource(Long sessionId) {
        // Hibernate follow-on locking can load the joined change before acquiring the session lock.
        jdbc.query("SELECT id FROM work_session WHERE id=? FOR NO KEY UPDATE",(rs,index)->rs.getLong(1),sessionId);
    }
    public SourceUpdateOperation create(Long sessionId, Long operatorId, DevelopmentChangeSourceUpdateCommand command,
            String originalSourceCommit, String originalFingerprint) {
        var owner = command.owner();
        jdbc.update("""
                INSERT INTO mobile_source_update_operation
                (id,idempotency_key,session_id,operator_id,publication_receipt_sha256,state,command_json,
                    original_source_commit,original_fingerprint_sha256)
                VALUES (?,?,?,?,?,'QUEUED',?::jsonb,?,?)
                """, owner.operationId(), owner.idempotencyKey(), sessionId, operatorId,
                command.publicationReceiptSha256(), json(command), originalSourceCommit, originalFingerprint);
        return get(owner.operationId(), false);
    }
    public void requireIdle() {
        jdbc.execute("SELECT pg_advisory_xact_lock(814205002)");
        Long active = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM agent_run WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED'))
                     + (SELECT count(*) FROM validation_operation WHERE status='RUNNING')
                     + (SELECT count(*) FROM mobile_delivery_operation WHERE state NOT IN
                         ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED','ROLLBACK_FAILED'))
                """, Long.class);
        if (active == null || active != 0) throw new DeliveryRejectedException("SOURCE_UPDATE_EXECUTION_ACTIVE");
    }
    public boolean lease(UUID id, String expectedState, Duration duration) {
        return jdbc.update("""
                UPDATE mobile_source_update_operation SET lease_until=now()+(? * interval '1 second'),
                    state=CASE WHEN state='QUEUED' THEN 'PREPARE_CLAIMED' ELSE state END,updated_at=now()
                WHERE id=? AND state=? AND lease_until<=now()
                """, duration.toSeconds(), id, expectedState) == 1;
    }
    public List<UUID> pending() {
        return jdbc.query("""
                SELECT id FROM mobile_source_update_operation WHERE
                    state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','READY_TO_RESOLVE','RESOLVING')
                    AND lease_until<=now() ORDER BY updated_at LIMIT 10
                """, (rs, index) -> rs.getObject("id", UUID.class));
    }
    public void state(UUID id, String state, String code) {
        jdbc.update("UPDATE mobile_source_update_operation SET state=?,error_code=?,updated_at=now(),lease_until=now() WHERE id=?",
                state, code, id);
    }
    public void prepared(UUID id, Preparation preparation, long revision, String state) {
        jdbc.update("""
                UPDATE mobile_source_update_operation SET preparation_json=?::jsonb,prepared_revision=?,state=?,
                    error_code=NULL,updated_at=now(),lease_until=now() WHERE id=?
                """, json(preparation), revision, state, id);
    }
    public void turn(UUID id, Long turnId) {
        jdbc.update("UPDATE mobile_source_update_operation SET resolver_turn_id=? WHERE id=?", turnId, id);
    }
    public void run(UUID id, Long runId) {
        jdbc.update("UPDATE mobile_source_update_operation SET resolver_run_id=?,state='RESOLVING',updated_at=now(),lease_until=now() WHERE id=?",
                runId, id);
    }
    public void completed(UUID id, long revision, String fingerprint) {
        jdbc.update("""
                UPDATE mobile_source_update_operation SET state='RESOLVER_COMPLETED',result_revision=?,result_fingerprint_sha256=?,
                    error_code=NULL,updated_at=now(),lease_until=now() WHERE id=?
                """, revision, fingerprint, id);
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid source update evidence", invalid); }
    }
    private SourceUpdateOperation row(ResultSet rs, int index) throws SQLException {
        try {
            String prep = rs.getString("preparation_json");
            return new SourceUpdateOperation(rs.getObject("id", UUID.class), rs.getLong("session_id"), rs.getLong("operator_id"),
                    rs.getString("state"), rs.getString("original_source_commit"), rs.getString("original_fingerprint_sha256"),
                    mapper.readValue(rs.getString("command_json"), DevelopmentChangeSourceUpdateCommand.class),
                    prep == null ? null : mapper.readValue(prep, Preparation.class), rs.getObject("prepared_revision", Long.class),
                    rs.getObject("resolver_turn_id", Long.class), currentResolver(rs.getObject("id",UUID.class),rs.getObject("resolver_run_id", Long.class)),
                    currentResolverRevision(rs.getObject("id",UUID.class),rs.getObject("prepared_revision",Long.class)),
                    rs.getObject("result_revision", Long.class), rs.getString("result_fingerprint_sha256"),
                    rs.getString("error_code"), rs.getTimestamp("updated_at").toInstant());
        } catch (java.io.IOException invalid) { throw new SQLException("Invalid source update evidence", invalid); }
    }

    private Long currentResolver(UUID operationId, Long original) {
        return jdbc.query("SELECT run_id FROM mobile_source_resolver_retry WHERE operation_id=? AND run_id IS NOT NULL ORDER BY created_at DESC,id DESC LIMIT 1",
            (rs,index)->rs.getLong(1),operationId).stream().findFirst().orElse(original);
    }

    private Long currentResolverRevision(UUID operationId, Long prepared) {
        return jdbc.query("SELECT source_revision FROM mobile_source_resolver_retry WHERE operation_id=? AND run_id IS NOT NULL ORDER BY created_at DESC,id DESC LIMIT 1",
            (rs,index)->rs.getLong(1),operationId).stream().findFirst().orElse(prepared);
    }

    public Optional<Long> retryRun(UUID operationId, Long sourceRunId) {
        return jdbc.query("SELECT run_id FROM mobile_source_resolver_retry WHERE operation_id=? AND source_run_id=? AND run_id IS NOT NULL",
            (rs,index)->rs.getLong(1),operationId,sourceRunId).stream().findFirst();
    }

    public UUID authorizeRetry(UUID operationId, Long sourceRunId, Long actor, long revision, boolean dirty,
            com.atenea.remoteworker.RemoteWorkerClient.SourceTreeFingerprint observed) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO mobile_source_resolver_retry(id,operation_id,source_run_id,operator_id,source_revision,
                observed_fingerprint_sha256,workspace_dirty,observation_json)
            VALUES (?,?,?,?,?,?,?,?::jsonb)
            """,id,operationId,sourceRunId,actor,revision,observed.fingerprintSha256(),dirty,json(observed));
        state(operationId,"RETRY_REQUESTED",null);
        return id;
    }

    public boolean matchesResolver(SourceUpdateOperation op, com.atenea.persistence.worksession.AgentRunEntity run) {
        if (!java.util.Objects.equals(op.resolverRunId(),run.getId())) return false;
        // AgentRun retains the raw observation even when CLEAN; its wire ownership uses null for clean source.
        var retry=jdbc.query("SELECT source_revision,observed_fingerprint_sha256 FROM mobile_source_resolver_retry WHERE operation_id=? AND run_id=?",
            (rs,index)->java.util.Objects.equals(run.getChangeSourceRevision(),rs.getLong(1))
                && java.util.Objects.equals(run.getChangeSourceFingerprintSha256(),rs.getString(2)),op.id(),run.getId());
        return retry.isEmpty() ? java.util.Objects.equals(run.getChangeSourceRevision(),op.preparedRevision())
            && java.util.Objects.equals(run.getChangeSourceFingerprintSha256(),op.preparation().preparedFingerprintSha256()) : retry.getFirst();
    }

    public void retried(UUID retryId, Long runId, UUID operationId) {
        if (jdbc.update("UPDATE mobile_source_resolver_retry SET run_id=? WHERE id=? AND operation_id=? AND run_id IS NULL",runId,retryId,operationId)!=1) {
            throw new DeliveryRejectedException("SOURCE_UPDATE_RETRY_EVIDENCE_MISMATCH");
        }
        state(operationId,"RESOLVING",null);
    }
}
