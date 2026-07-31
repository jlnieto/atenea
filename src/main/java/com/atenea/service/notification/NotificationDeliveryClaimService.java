package com.atenea.service.notification;

import com.atenea.persistence.notification.NotificationDeliveryEntity;
import com.atenea.persistence.notification.NotificationDeliveryRepository;
import com.atenea.persistence.notification.NotificationDeliveryState;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDeliveryClaimService {

    private final NotificationDeliveryRepository deliveryRepository;
    private final Clock clock;

    @Autowired
    public NotificationDeliveryClaimService(NotificationDeliveryRepository deliveryRepository) {
        this(deliveryRepository, Clock.systemUTC());
    }

    NotificationDeliveryClaimService(NotificationDeliveryRepository deliveryRepository, Clock clock) {
        this.deliveryRepository = deliveryRepository;
        this.clock = clock;
    }

    @Transactional
    public NotificationDeliveryCommand claim(Long deliveryId) {
        NotificationDeliveryEntity delivery = deliveryRepository.findByIdForUpdate(deliveryId).orElse(null);
        if (delivery == null || delivery.getState() != NotificationDeliveryState.PENDING) {
            return null;
        }
        Instant now = clock.instant();
        if (!delivery.getExpiresAt().isAfter(now)) {
            delivery.setState(NotificationDeliveryState.EXPIRED);
            delivery.setDiagnosticCode("DELIVERY_EXPIRED");
            delivery.setUpdatedAt(now);
            deliveryRepository.save(delivery);
            return null;
        }
        delivery.setState(NotificationDeliveryState.SENDING);
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setUpdatedAt(now);
        deliveryRepository.save(delivery);

        var event = delivery.getEvent();
        return new NotificationDeliveryCommand(
                delivery.getId(),
                delivery.getDevice().getPushToken(),
                event.getSafeTitle(),
                event.getSafeBody(),
                Map.of(
                        "type", event.getCategory().name(),
                        "notificationEventId", event.getId().toString(),
                        "templateVersion", event.getTemplateVersion(),
                        "deepLinkKind", event.getDeepLinkKind(),
                        "sessionId", event.getSession().getId(),
                        "runId", event.getAgentRun().getId()
                ));
    }

    @Transactional
    public void delivered(Long deliveryId) {
        NotificationDeliveryEntity delivery = deliveryRepository.findByIdForUpdate(deliveryId).orElse(null);
        if (delivery == null || delivery.getState() != NotificationDeliveryState.SENDING) {
            return;
        }
        Instant now = clock.instant();
        delivery.setState(NotificationDeliveryState.DELIVERED);
        delivery.setDeliveredAt(now);
        delivery.setDiagnosticCode(null);
        delivery.setUpdatedAt(now);
        deliveryRepository.save(delivery);
    }

    @Transactional
    public void failed(Long deliveryId) {
        NotificationDeliveryEntity delivery = deliveryRepository.findByIdForUpdate(deliveryId).orElse(null);
        if (delivery == null || delivery.getState() != NotificationDeliveryState.SENDING) {
            return;
        }
        delivery.setState(NotificationDeliveryState.FAILED);
        delivery.setDiagnosticCode("FCM_SEND_FAILED");
        delivery.setUpdatedAt(clock.instant());
        deliveryRepository.save(delivery);
    }
}
