package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.attachments.*;
import com.atenea.delivery.*;
import com.atenea.github.GitHubClient;
import com.atenea.mobilepush.FcmPushSender;
import com.atenea.persistence.auth.*;
import com.atenea.persistence.worksession.AttachmentStorageScope;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={"atenea.auth.bootstrap.enabled=false","atenea.attachments.enabled=true","atenea.attachments.real-project-allowlist[0]=atenea"})
class ProductUpdateAnnouncementIntegrationTest {
    @Autowired ProductUpdateAnnouncementService service;
    @Autowired OperatorRepository operators;
    @Autowired OperatorPushDeviceRepository devices;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean ReleaseControlClient release;
    @MockBean RemoteWorkerClient worker;
    @MockBean AttachmentWorkerClient attachments;
    @MockBean FcmPushSender fcm;
    @MockBean GitHubClient github;
    private AuthenticatedOperator actor;
    @BeforeEach void fixture() {
        Instant now=Instant.now();var operator=new OperatorEntity();operator.setEmail(UUID.randomUUID()+"@test.invalid");operator.setDisplayName("Test");
        operator.setPasswordHash("synthetic");operator.setActive(true);operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        operator.setCreatedAt(now);operator.setUpdatedAt(now);operators.saveAndFlush(operator);actor=new AuthenticatedOperator(operator.getId(),operator.getEmail(),"Test");
        var device=new OperatorPushDeviceEntity();device.setOperator(operator);device.setPushToken("synthetic-fcm-token-"+UUID.randomUUID());
        device.setPlatform("android");device.setDeviceName("Ephemeral test");device.setActive(true);device.setCreatedAt(now);device.setUpdatedAt(now);device.setLastRegisteredAt(now);devices.saveAndFlush(device);
        var app=mapper.createObjectNode().put("protocol","atenea-app-observation/v1").put("target","APP_PROD").put("state","OBSERVED")
                .put("sourceCommit","8".repeat(40)).put("imageSha256","a".repeat(64)).put("healthy",true).put("observedAt",now.getEpochSecond())
                .put("planId",UUID.randomUUID().toString()).put("operationId",UUID.randomUUID().toString()).put("receiptSha256","b".repeat(64)).put("finishedAt",1L);
        var apk=mapper.createObjectNode().put("protocol","atenea-android-observation/v1").put("target","ANDROID_STABLE").put("state","OBSERVED")
                .put("sourceCommit","8".repeat(40)).put("apkSha256","a".repeat(64)).put("certificateSha256","b".repeat(64))
                .put("versionCode",1000012).put("versionName","0.6.12").put("observedAt",now.getEpochSecond())
                .put("planId",UUID.randomUUID().toString()).put("operationId",UUID.randomUUID().toString()).put("receiptSha256","b".repeat(64)).put("finishedAt",1L);
        var platform=mapper.createObjectNode().put("protocol","atenea-platform-observation/v1").put("target","AX42_PLATFORM").put("state","OBSERVED")
                .put("sourceCommit","6".repeat(40)).put("installationSha256","a".repeat(64)).put("observedAt",now.getEpochSecond())
                .put("planId",UUID.randomUUID().toString()).put("operationId",UUID.randomUUID().toString()).put("receiptSha256","b".repeat(64)).put("finishedAt",1L);
        when(release.observeApp()).thenReturn(app);when(release.observeAndroid()).thenReturn(apk);when(release.observePlatform()).thenReturn(platform);
        var health=mock(RemoteWorkerClient.Health.class);when(health.healthy()).thenReturn(true);when(health.workerId()).thenReturn("ax42-01");when(health.capabilities()).thenReturn(List.of("project-codex-v4"));when(worker.health()).thenReturn(health);
        when(attachments.changeProjectCapability()).thenReturn(new AttachmentWorkerClient.RealProjectCapability(AttachmentWorkerClient.CHANGE_PROJECT_PROTOCOL,
                "ax42-01",true,List.of("atenea"),List.of(AttachmentStorageScope.REAL_SESSION),now));when(fcm.isReady()).thenReturn(true);
    }
    @AfterEach void removeOnlyThisTestsCommittedFixture() {
        if (actor==null) return;
        jdbc.update("DELETE FROM product_update_announcement WHERE operator_id=?",actor.operatorId());
        jdbc.update("DELETE FROM operator_push_device WHERE operator_id=?",actor.operatorId());
        operators.deleteById(actor.operatorId());
    }
    @Test void intentCommitsBeforeFcmAndReplaysWithoutDuplicatingPushOrCreatingSessions() {
        long sessions=jdbc.queryForObject("SELECT count(*) FROM work_session",Long.class);
        doAnswer(call -> {
            assertEquals("CLAIMED",jdbc.queryForObject("SELECT state FROM product_update_announcement WHERE operator_id=?",String.class,actor.operatorId()));
            List<FcmPushSender.FcmPushMessage> messages=call.getArgument(0);assertEquals(1,messages.size());
            assertEquals("atenea://updates",messages.getFirst().data().get("deepLink"));assertTrue(messages.getFirst().body().contains("Ajustes → Actualizaciones"));
            return null;
        }).when(fcm).send(anyList());
        var first=service.announce(actor);var repeat=service.announce(actor);
        assertEquals("ACCEPTED",first.state());assertEquals(first.id(),repeat.id());verify(fcm,times(1)).send(anyList());
        assertEquals(sessions,jdbc.queryForObject("SELECT count(*) FROM work_session",Long.class));
    }
    @Test void ambiguousFcmResponseStaysDurableAndIsNotRetried() {
        doThrow(new IllegalStateException("ephemeral unavailable transport")).when(fcm).send(anyList());
        var first=service.announce(actor);var repeat=service.announce(actor);
        assertEquals("UNCERTAIN",first.state());assertEquals("PUSH_DELIVERY_UNCERTAIN",repeat.errorCode());assertEquals(first.id(),repeat.id());
        verify(fcm,times(1)).send(anyList());
    }
    @Test void mismatchingApkCannotAnnounceProductReady() {
        var apk=release.observeAndroid().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)apk).put("sourceCommit","7".repeat(40));when(release.observeAndroid()).thenReturn(apk);
        assertEquals("PRODUCT_RELEASE_NOT_READY",assertThrows(DeliveryRejectedException.class,() -> service.announce(actor)).code());verify(fcm,never()).send(anyList());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM product_update_announcement WHERE operator_id=?",Integer.class,actor.operatorId()));
    }
    @Test void currentDatabaseRoleIsRequired() {
        var operator=operators.findById(actor.operatorId()).orElseThrow();operator.setCodexOperationsRole(CodexOperationsRole.ROUTINE_OPERATOR);operators.saveAndFlush(operator);
        assertEquals("PLATFORM_ADMINISTRATOR_REQUIRED",assertThrows(DeliveryRejectedException.class,() -> service.announce(actor)).code());verify(fcm,never()).send(anyList());
    }
    @Test void unconfiguredFcmCannotBeRecordedAsAnAcceptedNotice() {
        when(fcm.isReady()).thenReturn(false);
        assertEquals("PUSH_NOT_READY",assertThrows(DeliveryRejectedException.class,() -> service.announce(actor)).code());
        verify(fcm,never()).send(anyList());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM product_update_announcement WHERE operator_id=?",Integer.class,actor.operatorId()));
    }
    @Test void incompatibleChangeImagesCannotAnnounceTheProductReady() {
        when(attachments.changeProjectCapability()).thenReturn(null);
        assertEquals("PRODUCT_RELEASE_NOT_READY",assertThrows(DeliveryRejectedException.class,() -> service.announce(actor)).code());
        verify(fcm,never()).send(anyList());
    }
}
