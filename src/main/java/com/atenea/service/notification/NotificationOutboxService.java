package com.atenea.service.notification;

import com.atenea.persistence.auth.OperatorPushDeviceEntity;
import com.atenea.persistence.auth.OperatorPushDeviceRepository;
import com.atenea.persistence.notification.NotificationCategory;
import com.atenea.persistence.notification.NotificationDeliveryEntity;
import com.atenea.persistence.notification.NotificationDeliveryRepository;
import com.atenea.persistence.notification.NotificationDeliveryState;
import com.atenea.persistence.notification.NotificationEventEntity;
import com.atenea.persistence.notification.NotificationEventRepository;
import com.atenea.persistence.notification.NotificationPreferenceRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationOutboxService {

    static final String TEMPLATE_VERSION = NotificationTemplateCatalog.TEMPLATE_VERSION;
    static final String DEEP_LINK_KIND = "WORK_SESSION_CONVERSATION";
    static final String CHANNEL = "FCM";
    static final Duration DELIVERY_TTL = Duration.ofHours(24);

    private final AgentRunRepository agentRunRepository;
    private final NotificationEventRepository eventRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final OperatorPushDeviceRepository deviceRepository;
    private final Clock clock;

    @Autowired
    public NotificationOutboxService(
            AgentRunRepository agentRunRepository,
            NotificationEventRepository eventRepository,
            NotificationDeliveryRepository deliveryRepository,
            NotificationPreferenceRepository preferenceRepository,
            OperatorPushDeviceRepository deviceRepository) {
        this(agentRunRepository, eventRepository, deliveryRepository,
                preferenceRepository, deviceRepository, Clock.systemUTC());
    }

    NotificationOutboxService(
            AgentRunRepository agentRunRepository,
            NotificationEventRepository eventRepository,
            NotificationDeliveryRepository deliveryRepository,
            NotificationPreferenceRepository preferenceRepository,
            OperatorPushDeviceRepository deviceRepository,
            Clock clock) {
        this.agentRunRepository = agentRunRepository;
        this.eventRepository = eventRepository;
        this.deliveryRepository = deliveryRepository;
        this.preferenceRepository = preferenceRepository;
        this.deviceRepository = deviceRepository;
        this.clock = clock;
    }

    @Transactional
    public NotificationOutboxResult record(
            Long runId,
            NotificationCategory category,
            long sourceRevision) {
        Objects.requireNonNull(category, "category");
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("Notification source revision must be non-negative");
        }
        AgentRunEntity run = agentRunRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new com.atenea.service.worksession.AgentRunNotFoundException(runId));
        assertCategoryMatchesRun(run, category);

        String digest = digest(category, runId, sourceRevision);
        NotificationEventEntity existing = eventRepository.findByDeduplicationSha256(digest).orElse(null);
        if (existing != null) {
            return new NotificationOutboxResult(
                    existing,
                    false,
                    Math.toIntExact(deliveryRepository.countByEventId(existing.getId())));
        }

        Instant now = clock.instant();
        NotificationEventEntity event = new NotificationEventEntity();
        NotificationTemplateCatalog.SafeTemplate template =
                NotificationTemplateCatalog.resolve(TEMPLATE_VERSION, category);
        event.setId(UUID.randomUUID());
        event.setDeduplicationSha256(digest);
        event.setCategory(category);
        event.setTemplateVersion(TEMPLATE_VERSION);
        event.setDeepLinkKind(DEEP_LINK_KIND);
        event.setSession(run.getSession());
        event.setAgentRun(run);
        event.setSourceRevision(sourceRevision);
        event.setSafeTitle(template.title());
        event.setSafeBody(template.body());
        event.setCreatedAt(now);
        event = eventRepository.save(event);

        List<OperatorPushDeviceEntity> devices = deviceRepository.findByActiveTrueOrderByUpdatedAtDesc();
        int deliveries = 0;
        for (OperatorPushDeviceEntity device : devices) {
            boolean enabled = preferenceRepository.findByDeviceIdAndCategory(device.getId(), category)
                    .map(preference -> preference.isEnabled())
                    .orElse(true);
            if (!enabled) {
                continue;
            }
            NotificationDeliveryEntity delivery = new NotificationDeliveryEntity();
            delivery.setEvent(event);
            delivery.setDevice(device);
            delivery.setChannel(CHANNEL);
            delivery.setState(NotificationDeliveryState.PENDING);
            delivery.setAttemptCount(0);
            delivery.setExpiresAt(now.plus(DELIVERY_TTL));
            delivery.setCreatedAt(now);
            delivery.setUpdatedAt(now);
            deliveryRepository.save(delivery);
            deliveries++;
        }
        return new NotificationOutboxResult(event, true, deliveries);
    }

    private static void assertCategoryMatchesRun(
            AgentRunEntity run,
            NotificationCategory category) {
        if (category == NotificationCategory.RUN_COMPLETED
                && run.getStatus() != AgentRunStatus.SUCCEEDED) {
            throw new IllegalStateException("Completion notification requires a succeeded AgentRun");
        }
        if (category == NotificationCategory.RUN_FAILED
                && run.getStatus() != AgentRunStatus.FAILED) {
            throw new IllegalStateException("Failure notification requires a failed AgentRun");
        }
    }

    private static String digest(NotificationCategory category, Long runId, long revision) {
        String canonical = TEMPLATE_VERSION + "\n" + category.name() + "\n" + runId + "\n" + revision;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
