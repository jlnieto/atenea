package com.atenea.service.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.attachments.*;
import com.atenea.delivery.*;
import com.atenea.mobilepush.FcmPushSender;
import com.atenea.persistence.auth.*;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One explicit, durable product-ready notice, never a fabricated AgentRun notification. */
@Service
public class ProductUpdateAnnouncementService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProductUpdateAnnouncementService.class);
    private final OperatorRepository operators;
    private final OperatorPushDeviceRepository devices;
    private final ReleaseControlClient release;
    private final RemoteWorkerClient worker;
    private final AttachmentAdmissionPolicy attachments;
    private final AttachmentWorkerClient attachmentWorker;
    private final FcmPushSender fcm;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private MobileNotificationInboxService inboxService;
    @org.springframework.beans.factory.annotation.Autowired
    void setInboxService(MobileNotificationInboxService inboxService) { this.inboxService = inboxService; }
    public ProductUpdateAnnouncementService(OperatorRepository operators,OperatorPushDeviceRepository devices,
            ReleaseControlClient release,RemoteWorkerClient worker,AttachmentAdmissionPolicy attachments,
            AttachmentWorkerClient attachmentWorker,FcmPushSender fcm,JdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.operators=operators;this.devices=devices;this.release=release;this.worker=worker;this.attachments=attachments;
        this.attachmentWorker=attachmentWorker;this.fcm=fcm;this.jdbc=jdbc;this.mapper=mapper;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Announcement announce(AuthenticatedOperator actor) {
        administrator(actor);
        var app=ReleaseControlClient.verifyAppObservation(release.observeApp());
        var apk=ReleaseControlClient.verifyAndroidObservation(release.observeAndroid());
        var platform=ReleaseControlClient.verifyPlatformObservation(release.observePlatform());
        var health=worker.health();var images=attachmentWorker.changeProjectCapability();
        if (!app.healthy() || !app.sourceCommit().equals(apk.sourceCommit()) || !health.healthy() || !"ax42-01".equals(health.workerId())
                || health.capabilities()==null || !health.capabilities().contains("project-codex-v4")
                || !attachments.isRealCreateBindEnabled("atenea") || images==null || !images.healthy()
                || !AttachmentWorkerClient.CHANGE_PROJECT_PROTOCOL.equals(images.protocolVersion()) || !"ax42-01".equals(images.workerId())
                || !List.of("atenea").equals(images.projectIdentities()) || !List.of(com.atenea.persistence.worksession.AttachmentStorageScope.REAL_SESSION).equals(images.storageScopes())) {
            throw new DeliveryRejectedException("PRODUCT_RELEASE_NOT_READY");
        }
        if (!fcm.isReady()) throw new DeliveryRejectedException("PUSH_NOT_READY");
        var selected=devices.findByOperatorIdOrderByUpdatedAtDesc(actor.operatorId()).stream()
                .filter(d -> d.isActive() && "android".equalsIgnoreCase(d.getPlatform())).toList();
        if (selected.isEmpty()) throw new DeliveryRejectedException("NO_ACTIVE_ANDROID_DEVICE");
        UUID id=UUID.randomUUID();
        Map<String,Object> evidence=Map.of("backend",app,"android",apk,"platform",platform,"workerHealthy",true,
                "deviceIds",selected.stream().map(OperatorPushDeviceEntity::getId).toList());
        String json;
        try { json=mapper.writeValueAsString(evidence); }
        catch (Exception invalid) { throw new DeliveryRejectedException("ANNOUNCEMENT_EVIDENCE_INVALID"); }
        var claim=Objects.requireNonNull(transaction.execute(ignored -> {
            int created=jdbc.update("""
                    INSERT INTO product_update_announcement(id,operator_id,source_commit,version_code,version_name,state,device_count,evidence_json)
                    VALUES(?,?,?,?,?,'CLAIMED',?,?::jsonb) ON CONFLICT(operator_id,source_commit,version_code) DO NOTHING
                    """,id,actor.operatorId(),app.sourceCommit(),apk.versionCode(),apk.versionName(),selected.size(),json);
            var retained=jdbc.query("SELECT * FROM product_update_announcement WHERE operator_id=? AND source_commit=? AND version_code=?",
                    (rs,n) -> new Announcement(rs.getObject("id",UUID.class),rs.getString("state"),rs.getString("source_commit"),
                            rs.getString("version_name"),rs.getInt("version_code"),rs.getInt("device_count"),rs.getString("error_code")),
                    actor.operatorId(),app.sourceCommit(),apk.versionCode()).getFirst();
            return new Claim(created==1,retained);
        }));
        if (!claim.created()) return claim.value(); // Never replay an ambiguous external delivery.
        String state="ACCEPTED",code=null;
        try {
            fcm.send(selected.stream().map(device -> new FcmPushSender.FcmPushMessage(device.getPushToken(),
                    "Atenea ya está operativa",
                    "Actualiza a "+apk.versionName()+" / "+apk.versionCode()+" en Ajustes → Actualizaciones. Después abre el menú → Novedades para ver las mejoras y continúa tu mismo Cambio.",
                    Map.of("schemaVersion","atenea-product-update-data-v1","eventType","PRODUCT_UPDATE","type","PRODUCT_UPDATE",
                            "templateVersion","product-update-safe-v1","notificationEventId",id.toString(),
                            "deepLinkKind","ATENEA_UPDATES","deepLink","atenea://updates"))).toList());
        } catch (RuntimeException uncertain) { state="UNCERTAIN";code="PUSH_DELIVERY_UNCERTAIN"; }
        String finalState=state,finalCode=code;
        transaction.executeWithoutResult(ignored -> {
            if(jdbc.update("UPDATE product_update_announcement SET state=?,error_code=?,finished_at=CURRENT_TIMESTAMP WHERE id=? AND state='CLAIMED'",finalState,finalCode,id)!=1)
                throw new DeliveryRejectedException("ANNOUNCEMENT_EVIDENCE_MISMATCH");
        });
        if (inboxService != null) {
            try {
                operators.findById(actor.operatorId()).ifPresent(operator -> inboxService.record(operator,
                        id.toString(), "PRODUCT_UPDATE", "Atenea ya está operativa",
                        "Hay una actualización disponible. Revisa Novedades y Ajustes.", "UPDATES", null));
            } catch (RuntimeException exception) {
                log.warn("Could not record product update in notification inbox announcementId={}", id, exception);
            }
        }
        return new Announcement(id,state,app.sourceCommit(),apk.versionName(),apk.versionCode(),selected.size(),code);
    }
    private void administrator(AuthenticatedOperator actor) {
        if (actor==null || actor.operatorId()==null || operators.findById(actor.operatorId()).filter(o -> o.isActive() && o.getCodexOperationsRole()==CodexOperationsRole.PLATFORM_ADMINISTRATOR).isEmpty()) {
            throw new DeliveryRejectedException("PLATFORM_ADMINISTRATOR_REQUIRED");
        }
    }
    private record Claim(boolean created,Announcement value) { }
    public record Announcement(UUID id,String state,String sourceCommit,String versionName,int versionCode,int deviceCount,String errorCode) { }
}
