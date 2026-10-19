package com.atenea.service.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.atenea.mobilepush.*;
import com.atenea.persistence.auth.*;
import com.atenea.service.mobile.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;

/** An ephemeral PG16 database; no external worker, provider or shared runtime. */
@SpringBootTest(properties="atenea.auth.bootstrap.enabled=false")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class OperationsMonitoringDeliveryIntegrationTest {
    static final String DATABASE="atenea_test_monitor_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) throws Exception {
        String url=System.getenv("SPRING_DATASOURCE_URL");
        if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/atenea_test"))throw new IllegalStateException("Ephemeral PG16 required");
        try(var db=java.sql.DriverManager.getConnection(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));var s=db.createStatement()) {
            s.execute("CREATE DATABASE \""+DATABASE+"\"");
        }
        String target=url.substring(0,url.length()-"atenea_test".length())+DATABASE;
        org.flywaydb.core.Flyway.configure().dataSource(target,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"))
            .target("103").load().migrate();
        try(var db=java.sql.DriverManager.getConnection(target,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));var s=db.createStatement()) {
            s.execute("INSERT INTO managed_host(name,environment,ssh_host,ssh_user) VALUES('v103-preserved','test','synthetic.invalid','synthetic')");
        }
        p.add("spring.datasource.url",()->url.substring(0,url.length()-"atenea_test".length())+DATABASE);
        p.add("spring.flyway.schemas",()->"public");p.add("spring.flyway.default-schema",()->"public");
    }
    @AfterAll static void removeDatabase() throws Exception {
        try(var db=java.sql.DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));var s=db.createStatement()) {
            s.execute("DROP DATABASE \""+DATABASE+"\" WITH (FORCE)");
        }
    }
    @Autowired OperationsMonitoringNotifications notifications;
    @Autowired OperationsAlertDeliveryStore store;
    @Autowired OperatorRepository operators;
    @Autowired OperatorPushDeviceRepository devices;
    @Autowired MobileNotificationInboxService inbox;
    @Autowired AteneaUpdatesSnapshotStore snapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean(name="productNewsScheduler") ThreadPoolTaskScheduler newsScheduler;
    @MockBean(name="operationsProbeScheduler") ThreadPoolTaskScheduler probeScheduler;
    @MockBean(name="operationsPushScheduler") ThreadPoolTaskScheduler pushScheduler;
    Long host,operator;List<Long> deviceIds;
    final OperationsMonitoringIssue ssh=new OperationsMonitoringIssue("ssh","No se pudo comprobar Apache por SSH (código 255); estado desconocido.",true);
    final OperationsMonitoringIssue down=new OperationsMonitoringIssue("apache","Apache: inactive; Web Cliente: HTTP 503",false);
    @BeforeEach void fixture() {
        var now=Instant.now();var o=new OperatorEntity();o.setEmail(UUID.randomUUID()+"@monitor.test");o.setDisplayName("Synthetic");o.setPasswordHash("synthetic");o.setActive(true);o.setCreatedAt(now);o.setUpdatedAt(now);operators.saveAndFlush(o);operator=o.getId();
        deviceIds=new ArrayList<>();for(int i=0;i<2;i++) {
            var d=new OperatorPushDeviceEntity();d.setOperator(o);d.setPushToken("synthetic-"+UUID.randomUUID());d.setPlatform("android");d.setActive(true);d.setCreatedAt(now);d.setUpdatedAt(now);d.setLastRegisteredAt(now);devices.saveAndFlush(d);deviceIds.add(d.getId());
        }
        host=jdbc.queryForObject("INSERT INTO managed_host(name,environment,ssh_host,ssh_user) VALUES(?,'test','synthetic.invalid','synthetic') RETURNING id",Long.class,"monitor-"+UUID.randomUUID());
    }
    OperationsMonitoringResult check(OperationsMonitoringIssue... issues) {
        var run=jdbc.queryForObject("INSERT INTO operations_action_run(host_id,action,status,started_at,finished_at) VALUES(?,'SERVICE_CHECK','SUCCEEDED',now(),now()) RETURNING id",Long.class,host);
        return new OperationsMonitoringResult(host,"Synthetic",List.of(issues),run);
    }
    long deliveries(){return jdbc.queryForObject("SELECT count(*) FROM operations_alert_delivery WHERE host_id=?",Long.class,host);}
    @Test void v104AndHealthyCycleHaveNoPushOrFalseIncident(){
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='104' AND success",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success",Long.class));
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM managed_host WHERE name='v103-preserved'",Long.class));
        notifications.record(check());assertEquals(0,deliveries());assertEquals(0,inbox.list(operator,0).items().size());
    }
    @Test void consecutiveDegradationCreatesTwoEventsAndOneInboxItemPerOperatorPerCycle(){
        var first=check(down);notifications.record(first);notifications.record(first);
        notifications.record(check(down));
        assertEquals(4,deliveries());assertEquals(2L,jdbc.queryForObject("SELECT count(DISTINCT event_id) FROM operations_alert_delivery WHERE host_id=?",Long.class,host));
        var items=inbox.list(operator,0).items();assertEquals(2,items.size());assertTrue(items.getFirst().body().contains("Apache: inactive; Web Cliente: HTTP 503"));
        assertTrue(items.getFirst().body().contains("UTC"));assertEquals("OPERATIONS_DEGRADATION",items.getFirst().category());
    }
    @Test void sshConfirmationSurvivesServiceReconstructionAndMissingObservationIsNotRecovery(){
        notifications.record(check(ssh));notifications.record(check(ssh));assertEquals(0,deliveries());
        // A new service has no in-memory history; the third confirmation comes from PG.
        var restarted=new OperationsMonitoringNotifications(jdbc,mapper,devices,inbox);
        restarted.record(check(ssh));assertEquals(2,deliveries());
        restarted.unobserved(host);for(int i=0;i<4;i++)restarted.record(check(ssh));assertEquals(2,deliveries());
        assertEquals("OPERATIONS_VERIFICATION_FAILURE",inbox.list(operator,0).items().getFirst().category());
        assertTrue(inbox.list(operator,0).items().getFirst().title().startsWith("No se pudo verificar"));
        restarted.record(check());for(int i=0;i<3;i++)restarted.record(check(ssh));assertEquals(4,deliveries());
    }
    @Test void perDeviceRetriesKeepIdentityAndFencingAndDoNotResendToAcceptedDevice(){
        notifications.record(check(down));var ids=store.pending();assertEquals(2,ids.size());
        var accepted=store.claim(ids.getFirst());var failed=store.claim(ids.getLast());assertEquals(accepted.eventId(),failed.eventId());
        store.delivered(accepted);store.failed(failed,new FcmDeliveryException(FcmDeliveryException.FailureKind.RETRYABLE,"FCM_TRANSPORT_ERROR",null));
        assertNull(store.claim(accepted.id()));assertNull(store.claim(failed.id()));
        jdbc.update("UPDATE operations_alert_delivery SET next_attempt_at=now()-interval '1 second' WHERE id=?",failed.id());
        var retry=store.claim(failed.id());assertEquals(2,retry.attempt());assertEquals(failed.eventId(),retry.eventId());
        store.delivered(failed);assertEquals("SENDING",jdbc.queryForObject("SELECT state FROM operations_alert_delivery WHERE id=?",String.class,retry.id()));
        store.delivered(retry);assertTrue(store.pending().isEmpty());
    }
    @Test void lostLeaseAndInvalidTokenAreDurableAndCannotDisableARefreshedToken(){
        notifications.record(check(down));var original=store.claim(store.pending().getFirst());
        jdbc.update("UPDATE operations_alert_delivery SET lease_until=now()-interval '1 second' WHERE id=?",original.id());
        var resumed=store.claim(original.id());assertEquals(2,resumed.attempt());assertEquals(original.eventId(),resumed.eventId());
        jdbc.update("UPDATE operator_push_device SET push_token='synthetic-refreshed' WHERE id=?",resumed.deviceId());
        store.failed(resumed,new FcmDeliveryException(FcmDeliveryException.FailureKind.INVALID_TOKEN,"FCM_TOKEN_INVALID",null));
        assertEquals("INVALID_TOKEN",jdbc.queryForObject("SELECT state FROM operations_alert_delivery WHERE id=?",String.class,resumed.id()));
        assertEquals(true,jdbc.queryForObject("SELECT active FROM operator_push_device WHERE id=?",Boolean.class,resumed.deviceId()));
    }
    @Test void expiredAlertAndUnreadyFcmCannotBecomeDelivered(){
        notifications.record(check(down));var id=store.pending().getFirst();jdbc.update("UPDATE operations_alert_delivery SET expires_at=now()-interval '1 second' WHERE id=?",id);
        assertNull(store.claim(id));assertEquals("EXPIRED",jdbc.queryForObject("SELECT state FROM operations_alert_delivery WHERE id=?",String.class,id));
        var expiresAfterClaim=store.claim(store.pending().getFirst());
        store.failed(expiresAfterClaim,new FcmDeliveryException(FcmDeliveryException.FailureKind.PERMANENT,"DELIVERY_EXPIRED",null));
        assertEquals("EXPIRED",jdbc.queryForObject("SELECT state FROM operations_alert_delivery WHERE id=?",String.class,expiresAfterClaim.id()));
        var sender=mock(FcmPushSender.class);new OperationsAlertDispatcher(store,sender).dispatch();verify(sender,never()).send(anyList());
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM operations_alert_delivery WHERE host_id=? AND state='DELIVERED'",Long.class,host));
    }
    @Test void snapshotRoundTripAndFailedRefreshPreserveEvidence(){
        var at=Instant.parse("2026-10-19T12:00:00Z");var value=new AteneaUpdatesService.Updates("atenea-updates/v1",at,"CURRENT",at,null,null,null,true,List.of(),null,List.of());
        snapshots.save(1,value);snapshots.failed(1);var saved=new AteneaUpdatesSnapshotStore(jdbc,mapper).find(1);
        assertEquals(value,saved.payload());assertEquals("NEWS_REFRESH_UNAVAILABLE",saved.errorCode());
    }
    @Test void foreignCheckCannotBeBoundToAnotherHost(){
        var run=check(down);assertThrows(IllegalArgumentException.class,()->notifications.record(new OperationsMonitoringResult(host+10000,"Foreign",run.issues(),run.checkRunId())));
        assertEquals(0,deliveries());
    }
}
