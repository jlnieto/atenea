package com.atenea.delivery;

import com.atenea.mobilepush.FcmDeliveryException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ChangeProgressDeliveryStore {
    private final JdbcTemplate jdbc;
    public ChangeProgressDeliveryStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Command(UUID id,UUID claim,UUID event,Long session,String token,String title,String body,Instant expiresAt) { }
    public List<UUID> pending() {
        return jdbc.query("SELECT id FROM mobile_change_progress_delivery WHERE state IN ('PENDING','SENDING') AND next_attempt_at<=now() ORDER BY next_attempt_at LIMIT 10",
                (r,n)->r.getObject(1,UUID.class));
    }
    @Transactional
    public Command claim(UUID id) {
        var rows=jdbc.queryForList("""
                SELECT d.*,n.operation_id,n.id AS event_id,o.session_id,p.push_token,
                    (p.active AND a.active AND a.codex_operations_role='PLATFORM_ADMINISTRATOR'
                      AND p.operator_id=o.operator_id AND (w.status='OPEN' OR (o.kind='FINALIZE' AND o.state='SUCCEEDED'))
                      AND (o.state=n.outcome OR (n.outcome='INTEGRATION_READY' AND o.state='SUCCEEDED'
                        AND NOT EXISTS(SELECT 1 FROM mobile_delivery_operation i WHERE i.session_id=o.session_id
                          AND i.kind='INTEGRATE' AND i.state NOT IN ('FAILED','BLOCKED','ROLLED_BACK'))))) AS authorized
                FROM mobile_change_progress_delivery d JOIN mobile_change_progress_notice n ON n.id=d.notice_id
                JOIN mobile_delivery_operation o ON o.id=n.operation_id JOIN work_session w ON w.id=o.session_id
                JOIN operator_push_device p ON p.id=d.device_id JOIN operator_account a ON a.id=p.operator_id
                WHERE d.id=? AND d.state IN ('PENDING','SENDING') AND d.next_attempt_at<=now() FOR UPDATE OF d SKIP LOCKED
                """,id);
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();
        var expiry=((java.sql.Timestamp)row.get("expires_at")).toInstant();
        int attempts=((Number)row.get("attempts")).intValue();
        if(!Boolean.TRUE.equals(row.get("authorized")) || !expiry.isAfter(Instant.now()) || attempts>=5) {
            jdbc.update("UPDATE mobile_change_progress_delivery SET state='EXPIRED',diagnostic_code='NOTICE_NO_LONGER_CURRENT',claim_token=NULL,updated_at=now() WHERE id=?",id);
            return null;
        }
        UUID claim=UUID.randomUUID();
        jdbc.update("UPDATE mobile_change_progress_delivery SET state='SENDING',attempts=attempts+1,claim_token=?,next_attempt_at=now()+interval '60 seconds',updated_at=now() WHERE id=?",claim,id);
        return new Command(id,claim,(UUID)row.get("event_id"),((Number)row.get("session_id")).longValue(),
                (String)row.get("push_token"),(String)row.get("title"),(String)row.get("body"),expiry);
    }
    @Transactional
    public void accepted(Command command) {
        jdbc.update("UPDATE mobile_change_progress_delivery SET state='ACCEPTED',diagnostic_code=NULL,claim_token=NULL,updated_at=now() WHERE id=? AND state='SENDING' AND claim_token=?",
                command.id(),command.claim());
    }
    @Transactional
    public void failed(Command command,FcmDeliveryException failure) {
        if(failure.failureKind()==FcmDeliveryException.FailureKind.INVALID_TOKEN) jdbc.update("""
                UPDATE operator_push_device p SET active=false,updated_at=now() WHERE p.push_token=?
                  AND EXISTS(SELECT 1 FROM mobile_change_progress_delivery d WHERE d.id=? AND d.device_id=p.id
                    AND d.state='SENDING' AND d.claim_token=?)
                """,command.token(),command.id(),command.claim());
        jdbc.update("""
                UPDATE mobile_change_progress_delivery SET state=CASE WHEN ? OR attempts>=5 THEN 'FAILED' ELSE 'PENDING' END,
                  claim_token=NULL,diagnostic_code=?,next_attempt_at=now()+interval '30 seconds',updated_at=now()
                WHERE id=? AND state='SENDING' AND claim_token=?
                """,failure.failureKind()!=FcmDeliveryException.FailureKind.RETRYABLE,
                failure.diagnosticCode()!=null && failure.diagnosticCode().matches("[A-Z][A-Z0-9_]{2,79}") ? failure.diagnosticCode() : "FCM_TRANSPORT_ERROR",
                command.id(),command.claim());
    }
}
