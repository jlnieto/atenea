package com.atenea.service.operations;

import com.atenea.mobilepush.FcmDeliveryException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OperationsAlertDeliveryStore {
    private final JdbcTemplate jdbc;
    public OperationsAlertDeliveryStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Command(UUID id,int attempt,UUID eventId,Long hostId,String kind,String title,String body,Long deviceId,String pushToken,Instant expiresAt) { }
    public List<UUID> pending(){return jdbc.queryForList("""
        SELECT id FROM operations_alert_delivery WHERE state='PENDING'
         OR state='RETRY_WAIT' AND next_attempt_at<=now() OR state='SENDING' AND lease_until<=now()
        ORDER BY created_at LIMIT 25
        """,UUID.class);}
    @Transactional
    public Command claim(UUID id) {
        var rows=jdbc.queryForList("""
            SELECT d.*,p.push_token,p.active AS device_active,a.active AS operator_active FROM operations_alert_delivery d
            JOIN operator_push_device p ON p.id=d.device_id JOIN operator_account a ON a.id=p.operator_id
            WHERE d.id=? FOR UPDATE OF d
            """,id);
        if(rows.isEmpty())return null;
        var r=rows.getFirst();String state=(String)r.get("state");Instant now=Instant.now();
        if(!List.of("PENDING","RETRY_WAIT","SENDING").contains(state))return null;
        if(state.equals("RETRY_WAIT") && ((Timestamp)r.get("next_attempt_at")).toInstant().isAfter(now)
            || state.equals("SENDING") && ((Timestamp)r.get("lease_until")).toInstant().isAfter(now))return null;
        int attempt=((Number)r.get("attempt_count")).intValue()+1;
        if(!((Timestamp)r.get("expires_at")).toInstant().isAfter(now)) {terminal(id,"EXPIRED","DELIVERY_EXPIRED");return null;}
        if(!Boolean.TRUE.equals(r.get("device_active")) || !Boolean.TRUE.equals(r.get("operator_active"))) {terminal(id,"FAILED","DEVICE_INACTIVE");return null;}
        if(attempt>5){terminal(id,"FAILED","FCM_RETRY_EXHAUSTED");return null;}
        jdbc.update("UPDATE operations_alert_delivery SET state='SENDING',attempt_count=?,lease_until=?,next_attempt_at=NULL,diagnostic_code=NULL,updated_at=now() WHERE id=?",
            attempt,Timestamp.from(now.plusSeconds(60)),id);
        return new Command(id,attempt,(UUID)r.get("event_id"),((Number)r.get("host_id")).longValue(),(String)r.get("kind"),
            (String)r.get("title"),(String)r.get("body"),((Number)r.get("device_id")).longValue(),(String)r.get("push_token"),((Timestamp)r.get("expires_at")).toInstant());
    }
    private void terminal(UUID id,String state,String code){jdbc.update("UPDATE operations_alert_delivery SET state=?,diagnostic_code=?,lease_until=NULL,next_attempt_at=NULL,updated_at=now() WHERE id=?",state,code,id);}
    @Transactional
    public void delivered(Command command){jdbc.update("UPDATE operations_alert_delivery SET state='DELIVERED',lease_until=NULL,diagnostic_code=NULL,updated_at=now() WHERE id=? AND state='SENDING' AND attempt_count=?",command.id(),command.attempt());}
    @Transactional
    public void failed(Command command,FcmDeliveryException failure) {
        var rows=jdbc.queryForList("SELECT expires_at FROM operations_alert_delivery WHERE id=? AND state='SENDING' AND attempt_count=? FOR UPDATE",command.id(),command.attempt());
        if(rows.isEmpty())return;
        String state,code=failure.diagnosticCode();Instant next=null;
        if(failure.failureKind()==FcmDeliveryException.FailureKind.INVALID_TOKEN) {
            state="INVALID_TOKEN";
            jdbc.update("UPDATE operator_push_device SET active=false,updated_at=now() WHERE id=? AND push_token=?",command.deviceId(),command.pushToken());
        }else if("DELIVERY_EXPIRED".equals(code))state="EXPIRED";
        else if(failure.failureKind()==FcmDeliveryException.FailureKind.PERMANENT)state="FAILED";
        else {
            next=Instant.now().plusSeconds(30L << (command.attempt()-1));
            if(!next.isBefore(((Timestamp)rows.getFirst().get("expires_at")).toInstant())){state="EXPIRED";code="DELIVERY_EXPIRED";next=null;}
            else if(command.attempt()>=5){state="FAILED";code="FCM_RETRY_EXHAUSTED";next=null;}
            else state="RETRY_WAIT";
        }
        jdbc.update("UPDATE operations_alert_delivery SET state=?,diagnostic_code=?,next_attempt_at=?,lease_until=NULL,updated_at=now() WHERE id=? AND attempt_count=?",
            state,code,next==null ? null : Timestamp.from(next),command.id(),command.attempt());
    }
}
