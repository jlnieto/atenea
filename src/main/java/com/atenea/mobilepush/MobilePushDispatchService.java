package com.atenea.mobilepush;

import com.atenea.persistence.auth.MobilePushNotificationLogEntity;
import com.atenea.persistence.auth.MobilePushNotificationLogRepository;
import com.atenea.persistence.auth.OperatorPushDeviceEntity;
import com.atenea.persistence.auth.OperatorPushDeviceRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.SessionDeliverableEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.codexoperations.CodexSessionOperationsProperties;
import com.atenea.persistence.notification.NotificationCategory;
import com.atenea.service.notification.NotificationOutboxService;
import com.atenea.service.mobile.MobileNotificationInboxService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MobilePushDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MobilePushDispatchService.class);

    private final OperatorPushDeviceRepository operatorPushDeviceRepository;
    private final MobilePushNotificationLogRepository mobilePushNotificationLogRepository;
    private final FcmPushSender fcmPushSender;
    private final CodexSessionOperationsProperties codexOperationsProperties;
    private final NotificationOutboxService notificationOutboxService;
    private MobileNotificationInboxService inboxService;

    @org.springframework.beans.factory.annotation.Autowired
    void setInboxService(MobileNotificationInboxService inboxService) {
        this.inboxService = inboxService;
    }

    public MobilePushDispatchService(
            OperatorPushDeviceRepository operatorPushDeviceRepository,
            MobilePushNotificationLogRepository mobilePushNotificationLogRepository,
            FcmPushSender fcmPushSender,
            CodexSessionOperationsProperties codexOperationsProperties,
            NotificationOutboxService notificationOutboxService
    ) {
        this.operatorPushDeviceRepository = operatorPushDeviceRepository;
        this.mobilePushNotificationLogRepository = mobilePushNotificationLogRepository;
        this.fcmPushSender = fcmPushSender;
        this.codexOperationsProperties = codexOperationsProperties;
        this.notificationOutboxService = notificationOutboxService;
    }

    @Transactional
    public void notifyRunSucceeded(AgentRunEntity run) {
        if (codexOperationsProperties.isNotificationOutboxEnabled()) {
            notificationOutboxService.record(
                    run.getId(),
                    NotificationCategory.RUN_COMPLETED,
                    Math.max(0, run.getLifecycleRevision()));
            return;
        }
        sendOnce(
                "RUN_SUCCEEDED:" + run.getId(),
                "RUN_SUCCEEDED",
                run.getSession().getId(),
                run.getId(),
                null,
                "Codex ha respondido",
                "%s · %s".formatted(run.getSession().getProject().getName(), run.getSession().getTitle())
        );
    }

    @Transactional
    public void notifyRunFailed(AgentRunEntity run) {
        if (codexOperationsProperties.isNotificationOutboxEnabled()) {
            notificationOutboxService.record(
                    run.getId(),
                    NotificationCategory.RUN_FAILED,
                    Math.max(0, run.getLifecycleRevision()));
        }
    }

    @Transactional
    public void notifyRunActionRequired(AgentRunEntity run) {
        if (codexOperationsProperties.isNotificationOutboxEnabled()) {
            notificationOutboxService.record(
                    run.getId(),
                    NotificationCategory.ACTION_REQUIRED,
                    Math.max(0, run.getLifecycleRevision()));
        }
    }

    @Transactional
    public void notifyCloseBlocked(WorkSessionEntity session, String blockedState, String reason) {
        sendOnce(
                "CLOSE_BLOCKED:%s:%s".formatted(session.getId(), blockedState),
                "CLOSE_BLOCKED",
                session.getId(),
                null,
                null,
                "Session close blocked",
                "%s · %s".formatted(session.getProject().getName(), reason)
        );
    }

    @Transactional
    public void notifyPullRequestMerged(WorkSessionEntity session) {
        sendOnce(
                "PULL_REQUEST_MERGED:" + session.getId(),
                "PULL_REQUEST_MERGED",
                session.getId(),
                null,
                null,
                "Pull request merged",
                "%s · %s".formatted(session.getProject().getName(), session.getTitle())
        );
    }

    @Transactional
    public void notifyBillingReady(SessionDeliverableEntity deliverable) {
        sendOnce(
                "BILLING_READY:" + deliverable.getId(),
                "BILLING_READY",
                deliverable.getSession().getId(),
                null,
                deliverable.getId(),
                "Billing ready",
                "%s · %s".formatted(
                        deliverable.getSession().getProject().getName(),
                        deliverable.getTitle() == null ? deliverable.getSession().getTitle() : deliverable.getTitle()
                )
        );
    }

    public void notifyOperationsDegradation(Long hostId, String hostName, List<String> errors) {
        sendOperationsPush(hostId, "Degradación detectada en " + hostName, errors, "OPERATIONS_DEGRADATION");
    }

    public void notifyOperationsVerificationFailure(Long hostId, String hostName, List<String> errors) {
        sendOperationsPush(hostId, "No se pudo verificar " + hostName,
                errors, "OPERATIONS_VERIFICATION_FAILURE");
    }

    private void sendOperationsPush(Long hostId, String title, List<String> errors, String type) {
        List<OperatorPushDeviceEntity> devices = operatorPushDeviceRepository.findByActiveTrueOrderByUpdatedAtDesc();
        if (devices.isEmpty()) {
            return;
        }
        String candidateBody = errors.isEmpty() || errors.stream().anyMatch(error -> error.contains("{"))
                ? "Revisa el estado del host y las incidencias en Operaciones."
                : String.join("\n", errors);
        String body = candidateBody.length() > 300 ? candidateBody.substring(0, 299) + "…" : candidateBody;
        String eventId = java.util.UUID.randomUUID().toString();
        if (inboxService != null) {
            devices.stream().map(OperatorPushDeviceEntity::getOperator).distinct().forEach(operator ->
                    inboxService.record(operator, eventId, type, title, body, "OPERATIONS", hostId));
        }
        try {
            List<FcmPushSender.FcmPushMessage> fcmMessages = devices.stream()
                    .map(device -> new FcmPushSender.FcmPushMessage(
                            device.getPushToken(),
                            title,
                            body,
                            Map.of(
                                    "schemaVersion", "atenea-operations-notification-v1",
                                    "eventType", "OPERATIONS_ALERT",
                                    "type", type,
                                    "notificationEventId", eventId,
                                    "hostId", hostId,
                                    "deepLinkKind", "OPERATIONS_HOST",
                                    "deepLink", "atenea://operations/" + hostId)))
                    .toList();
            fcmPushSender.send(fcmMessages);
        } catch (Exception exception) {
            log.warn("Could not send operations degradation push hostId={}: {}", hostId, exception.getMessage());
        }
    }

    private void sendOnce(
            String eventKey,
            String eventType,
            Long sessionId,
            Long runId,
            Long deliverableId,
            String title,
            String body
    ) {
        if (mobilePushNotificationLogRepository.existsByEventKey(eventKey)) {
            return;
        }
        List<OperatorPushDeviceEntity> devices = operatorPushDeviceRepository.findByActiveTrueOrderByUpdatedAtDesc();
        if (devices.isEmpty()) {
            return;
        }
        String notificationEventId = java.util.UUID.randomUUID().toString();
        try {
            List<FcmPushSender.FcmPushMessage> fcmMessages = devices.stream()
                    .map(device -> new FcmPushSender.FcmPushMessage(
                            device.getPushToken(),
                            title,
                            body,
                            Map.of(
                                    "schemaVersion", "atenea-legacy-session-notification-v1",
                                    "eventType", eventType,
                                    "type", eventType,
                                    "notificationEventId", notificationEventId,
                                    "sessionId", sessionId,
                                    "deepLinkKind", "WORK_SESSION_CONVERSATION",
                                    "deepLink", "atenea://work-sessions/" + sessionId + "/conversation")))
                    .toList();
            fcmPushSender.send(fcmMessages);
            if (inboxService != null) {
                devices.stream().map(OperatorPushDeviceEntity::getOperator).distinct().forEach(operator ->
                        inboxService.record(operator, notificationEventId, eventType, title, body,
                                "CONVERSATION", sessionId));
            }
            Instant now = Instant.now();
            MobilePushNotificationLogEntity logEntry = new MobilePushNotificationLogEntity();
            logEntry.setEventKey(eventKey);
            logEntry.setEventType(eventType);
            logEntry.setSessionId(sessionId);
            logEntry.setRunId(runId);
            logEntry.setDeliverableId(deliverableId);
            logEntry.setTitle(title);
            logEntry.setBody(body);
            logEntry.setSentAt(now);
            logEntry.setCreatedAt(now);
            mobilePushNotificationLogRepository.save(logEntry);
        } catch (Exception exception) {
            log.warn("Could not send mobile push notification eventKey={}: {}", eventKey, exception.getMessage());
        }
    }
}
