package com.atenea.mobilepush;

import com.atenea.persistence.auth.MobilePushNotificationLogEntity;
import com.atenea.persistence.auth.MobilePushNotificationLogRepository;
import com.atenea.persistence.auth.OperatorPushDeviceEntity;
import com.atenea.persistence.auth.OperatorPushDeviceRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.SessionDeliverableEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
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
    private final ExpoPushSender expoPushSender;

    public MobilePushDispatchService(
            OperatorPushDeviceRepository operatorPushDeviceRepository,
            MobilePushNotificationLogRepository mobilePushNotificationLogRepository,
            ExpoPushSender expoPushSender
    ) {
        this.operatorPushDeviceRepository = operatorPushDeviceRepository;
        this.mobilePushNotificationLogRepository = mobilePushNotificationLogRepository;
        this.expoPushSender = expoPushSender;
    }

    @Transactional
    public void notifyRunSucceeded(AgentRunEntity run) {
        sendOnce(
                "RUN_SUCCEEDED:" + run.getId(),
                "RUN_SUCCEEDED",
                run.getSession().getId(),
                run.getId(),
                null,
                "Run completed",
                "%s · %s".formatted(run.getSession().getProject().getName(), run.getSession().getTitle()),
                Map.of(
                        "type", "RUN_SUCCEEDED",
                        "sessionId", run.getSession().getId(),
                        "runId", run.getId()
                )
        );
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
                "%s · %s".formatted(session.getProject().getName(), reason),
                Map.of(
                        "type", "CLOSE_BLOCKED",
                        "sessionId", session.getId(),
                        "state", blockedState
                )
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
                "%s · %s".formatted(session.getProject().getName(), session.getTitle()),
                Map.of(
                        "type", "PULL_REQUEST_MERGED",
                        "sessionId", session.getId()
                )
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
                ),
                Map.of(
                        "type", "BILLING_READY",
                        "sessionId", deliverable.getSession().getId(),
                        "deliverableId", deliverable.getId()
                )
        );
    }

    private void sendOnce(
            String eventKey,
            String eventType,
            Long sessionId,
            Long runId,
            Long deliverableId,
            String title,
            String body,
            Map<String, Object> data
    ) {
        if (mobilePushNotificationLogRepository.existsByEventKey(eventKey)) {
            return;
        }
        List<OperatorPushDeviceEntity> devices = operatorPushDeviceRepository.findByActiveTrueOrderByUpdatedAtDesc();
        if (devices.isEmpty()) {
            return;
        }
        try {
            expoPushSender.send(devices.stream()
                    .map(device -> new ExpoPushSender.ExpoPushMessage(
                            device.getExpoPushToken(),
                            title,
                            body,
                            "default",
                            data))
                    .toList());
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
