package com.atenea.service.operations;

import com.atenea.persistence.auth.*;
import com.atenea.service.mobile.MobileNotificationInboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Observation, counters, inbox and per-device outbox commit atomically. */
@Service
public class OperationsMonitoringNotifications {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    private final OperatorPushDeviceRepository devices;private final MobileNotificationInboxService inbox;
    public OperationsMonitoringNotifications(JdbcTemplate jdbc,ObjectMapper mapper,OperatorPushDeviceRepository devices,MobileNotificationInboxService inbox) {
        this.jdbc=jdbc;this.mapper=mapper;this.devices=devices;this.inbox=inbox;
    }
    private Map<String,OperationsAlertPolicy.IssueState> decode(String json) {
        try{return mapper.readValue(json,new TypeReference<Map<String,OperationsAlertPolicy.IssueState>>(){});}
        catch(java.io.IOException invalid){throw new IllegalStateException("Invalid monitoring state",invalid);}
    }
    private String encode(Object value){try{return mapper.writeValueAsString(value);}catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}}
    @Transactional
    public void record(OperationsMonitoringResult result) {
        if(result.checkRunId()==null || !Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM operations_action_run WHERE id=? AND host_id=? AND action='SERVICE_CHECK')
            """,Boolean.class,result.checkRunId(),result.hostId())))throw new IllegalArgumentException("Persisted monitoring owner required");
        jdbc.update("INSERT INTO operations_monitor_state(host_id) VALUES(?) ON CONFLICT DO NOTHING",result.hostId());
        var row=jdbc.queryForMap("SELECT * FROM operations_monitor_state WHERE host_id=? FOR UPDATE",result.hostId());
        var last=(Number)row.get("last_check_run_id");
        if(last!=null && last.longValue()>=result.checkRunId())return;
        var active=devices.findByActiveTrueOrderByUpdatedAtDesc().stream()
            .filter(d->d.getOperator().isActive() && "android".equalsIgnoreCase(d.getPlatform())).toList();
        var decision=OperationsAlertPolicy.observe(decode(row.get("issue_state_json").toString()),result.issues(),!active.isEmpty());
        if(!decision.alerts().isEmpty()) {
            String kind=decision.alerts().stream().allMatch(i->i.key().equals("ssh")) ? "OPERATIONS_VERIFICATION_FAILURE" : "OPERATIONS_DEGRADATION";
            String title=(kind.equals("OPERATIONS_VERIFICATION_FAILURE") ? "No se pudo verificar " : "Degradación detectada en ")+result.hostName();
            var checked=jdbc.queryForObject("SELECT finished_at FROM operations_action_run WHERE id=?",java.sql.Timestamp.class,result.checkRunId());
            String body="Comprobación "+java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm 'UTC'")
                .withZone(java.time.ZoneOffset.UTC).format(Objects.requireNonNull(checked).toInstant())+"\n"
                +String.join("\n",decision.alerts().stream().map(OperationsMonitoringIssue::message).toList());
            title=title.substring(0,Math.min(190,title.length()));body=body.substring(0,Math.min(500,body.length()));
            UUID event=UUID.randomUUID();var recorded=new HashSet<Long>();
            for(var device:active) {
                jdbc.update("""
                    INSERT INTO operations_alert_delivery(id,event_id,check_run_id,host_id,device_id,kind,title,body,state,created_at,updated_at,expires_at)
                    VALUES(?,?,?,?,?,?,?,?,'PENDING',now(),now(),now()+interval '10 minutes')
                    ON CONFLICT(check_run_id,kind,device_id) DO NOTHING
                    """,UUID.randomUUID(),event,result.checkRunId(),result.hostId(),device.getId(),kind,title,body);
                if(recorded.add(device.getOperator().getId()))inbox.record(device.getOperator(),event.toString(),kind,title,body,"OPERATIONS",result.hostId());
            }
        }
        jdbc.update("UPDATE operations_monitor_state SET last_check_run_id=?,issue_state_json=?::jsonb,observed_at=now() WHERE host_id=?",
            result.checkRunId(),encode(decision.state()),result.hostId());
    }
    @Transactional
    public void unobserved(Long hostId) {
        var state=jdbc.query("SELECT issue_state_json::text FROM operations_monitor_state WHERE host_id=? FOR UPDATE",(r,n)->r.getString(1),hostId);
        if(!state.isEmpty())jdbc.update("UPDATE operations_monitor_state SET issue_state_json=?::jsonb WHERE host_id=?",encode(OperationsAlertPolicy.unobserved(decode(state.getFirst()))),hostId);
    }
}
