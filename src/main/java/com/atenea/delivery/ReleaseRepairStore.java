package com.atenea.delivery;

import com.atenea.github.GitHubReleaseBuild;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Immutable authorization, not a second ticket or an instruction-bearing mobile payload. */
@Repository
public class ReleaseRepairStore {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    public ReleaseRepairStore(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }
    public record Binding(UUID id,Long sessionId,Long operatorId,UUID failedDeliveryId,UUID preparationId,
            String sourceCommit,String originalPrUrl,String originalHead,String originalMerge,
            JsonNode diagnostic,String repairHead,String repairPrUrl) { }
    public Binding failed(UUID id) {
        return rows("SELECT * FROM mobile_release_build_repair WHERE failed_delivery_id=?",id).stream().findFirst().orElse(null);
    }
    public Binding preparation(UUID id) {
        return rows("""
            WITH RECURSIVE lineage AS (
              SELECT id,predecessor_id,0 depth FROM mobile_source_update_operation WHERE id=?
              UNION ALL SELECT p.id,p.predecessor_id,l.depth+1 FROM mobile_source_update_operation p
              JOIN lineage l ON p.id=l.predecessor_id WHERE l.depth<30)
            SELECT r.* FROM mobile_release_build_repair r JOIN lineage l ON l.id=r.preparation_id ORDER BY l.depth LIMIT 1
            """,id).stream().findFirst().orElse(null);
    }
    public void create(Long actor,DeliveryOperation failed,WorkSessionEntity session,UUID preparation,
            String merge,GitHubReleaseBuild.Evidence proof,List<DeliveryTarget> targets) {
        if(!Objects.equals(failed.sourceCommit(),proof.sourceCommit()) || !"FAILED".equals(proof.state()) || proof.runId()==null)
            throw new DeliveryRejectedException("RELEASE_REPAIR_EVIDENCE_MISMATCH");
        jdbc.update("""
            INSERT INTO mobile_release_build_repair(id,session_id,operator_id,failed_delivery_id,preparation_id,
              source_commit,original_pr_url,original_head,original_merge,diagnostic_json,required_targets_json)
            VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
            """,UUID.randomUUID(),session.getId(),actor,failed.id(),preparation,proof.sourceCommit(),
            session.getPullRequestUrl(),session.getFinalCommitSha(),merge,json(proof),json(targets));
    }
    public boolean permitsFinalization(UUID preparation,WorkSessionEntity session) {
        var b=preparation(preparation);
        return b!=null && b.sessionId().equals(session.getId()) && b.originalPrUrl().equals(session.getPullRequestUrl())
            && b.originalHead().equals(session.getFinalCommitSha());
    }
    public void finalized(UUID preparation,WorkSessionEntity session,String head) {
        var b=preparation(preparation);
        if(b==null) return;
        if(b.repairPrUrl()!=null && b.repairPrUrl().equals(session.getPullRequestUrl())) return;
        if(!permitsFinalization(preparation,session) || b.repairHead()!=null && !b.repairHead().equals(head))
            throw new DeliveryRejectedException("RELEASE_REPAIR_EVIDENCE_MISMATCH");
        jdbc.update("UPDATE mobile_release_build_repair SET repair_head=? WHERE id=?",head,b.id());
    }
    public Binding replacement(WorkSessionEntity session,String newHead) {
        return rows("SELECT * FROM mobile_release_build_repair WHERE session_id=? AND original_pr_url=? AND repair_head=?",
            session.getId(),session.getPullRequestUrl(),newHead).stream().findFirst().orElse(null);
    }
    public boolean mayPrepareReplacement(WorkSessionEntity session) {
        return session.getAcceptanceState()==com.atenea.persistence.worksession.WorkSessionAcceptanceState.VALIDATED
            && !rows("SELECT * FROM mobile_release_build_repair WHERE session_id=? AND original_pr_url=? AND original_head=?",
                session.getId(),session.getPullRequestUrl(),session.getFinalCommitSha()).isEmpty();
    }
    public void published(Binding b,String url) {
        if(b.repairPrUrl()!=null && !b.repairPrUrl().equals(url)) throw new DeliveryRejectedException("RELEASE_REPAIR_PR_IDENTITY_MOVED");
        jdbc.update("UPDATE mobile_release_build_repair SET repair_pr_url=? WHERE id=?",url,b.id());
    }
    public void prepared(UUID preparation,String fingerprint) {
        var b=preparation(preparation);
        if(b!=null && b.preparationId().equals(preparation)) jdbc.update(
            "UPDATE mobile_release_build_repair SET prepared_fingerprint=? WHERE id=?",fingerprint,b.id());
    }
    public List<DeliveryTarget> retainedTargets(Long session) {
        var result=new java.util.TreeSet<DeliveryTarget>();
        jdbc.query("SELECT required_targets_json::text FROM mobile_release_build_repair WHERE session_id=?",rs->{
            try { for(var value:mapper.readTree(rs.getString(1))) result.add(DeliveryTarget.valueOf(value.asText())); }
            catch(Exception bad) { throw new DeliveryRejectedException("RELEASE_REPAIR_EVIDENCE_MISMATCH"); }
        },session);
        return List.copyOf(result);
    }
    private List<Binding> rows(String sql,Object... args) {
        return jdbc.query(sql,(rs,index)->{
            try { return new Binding(rs.getObject("id",UUID.class),rs.getLong("session_id"),rs.getLong("operator_id"),
                rs.getObject("failed_delivery_id",UUID.class),rs.getObject("preparation_id",UUID.class),rs.getString("source_commit"),
                rs.getString("original_pr_url"),rs.getString("original_head"),rs.getString("original_merge"),
                mapper.readTree(rs.getString("diagnostic_json")),rs.getString("repair_head"),rs.getString("repair_pr_url")); }
            catch(java.io.IOException bad) { throw new java.sql.SQLException("Invalid sealed build evidence",bad); }
        },args);
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch(Exception bad) { throw new IllegalStateException("Invalid build evidence"); }
    }
}
