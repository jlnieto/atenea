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
    public void lockReleaseIntent() { jdbc.execute("SELECT pg_advisory_xact_lock(814205001)"); }
    public void lockAdmissionAndRequireIdle() {
        jdbc.execute("SELECT pg_advisory_xact_lock(814205002)");
        Long active = jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",Long.class);
        if (active == null || active != 0L) throw new DeliveryRejectedException("ACTIVE_AGENT_RUN");
        Long preparation = jdbc.queryForObject("""
                SELECT count(*) FROM mobile_source_update_operation
                WHERE state IN ('QUEUED','PREPARE_CLAIMED','UNCERTAIN','ATTENTION','READY_TO_RESOLVE','RESOLVING')
                """, Long.class);
        if (preparation == null || preparation != 0L) throw new DeliveryRejectedException("SOURCE_UPDATE_IN_PROGRESS");
        Long finalization = jdbc.queryForObject("SELECT count(*) FROM mobile_source_finalization WHERE state <> 'PUBLISHED'",Long.class);
        if (finalization == null || finalization != 0L) throw new DeliveryRejectedException("SOURCE_FINALIZATION_IN_PROGRESS");
    }
    public java.util.Optional<DeliveryOperation> activeRelease() {
        return jdbc.query("SELECT * FROM mobile_delivery_operation WHERE kind='RELEASE' "
                + "AND state NOT IN ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED') LIMIT 1", this::row).stream().findFirst();
    }
    public java.util.Optional<DeliveryOperation> integrated(Long sessionId, String head) {
        return jdbc.query("SELECT * FROM mobile_delivery_operation WHERE session_id=? AND kind='INTEGRATE' "
                + "AND state='SUCCEEDED' AND source_commit=? ORDER BY created_at DESC LIMIT 1", this::row, sessionId, head).stream().findFirst();
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
        jdbc.update("""
                UPDATE mobile_delivery_operation SET state=?,error_code=?,plan_sha256=?,evidence_json=?::jsonb,
                  updated_at=now(),finished_at=CASE WHEN ? THEN now() ELSE NULL END WHERE id=?
                """, state, code, planSha, evidence.toString(), DeliveryOperation.TERMINAL.contains(state), op.id());
    }
    public List<UUID> pending() {
        return jdbc.query("""
                SELECT id FROM mobile_delivery_operation
                WHERE state NOT IN ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED','ROLLBACK_FAILED')
                  AND (state <> 'READY' OR (evidence_json->>'expiresAt')::bigint <= extract(epoch from now()))
                ORDER BY updated_at LIMIT 10
                """, (rs, index) -> rs.getObject("id", UUID.class));
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
