package com.atenea.service.notification;

import com.atenea.mobilepush.FcmDeliveryException;
import com.atenea.persistence.notification.NotificationDeliveryEntity;
import com.atenea.persistence.notification.NotificationDeliveryRepository;
import com.atenea.persistence.notification.NotificationDeliveryState;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDeliveryClaimService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(30);

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
        if (delivery == null || (delivery.getState() != NotificationDeliveryState.PENDING
                && delivery.getState() != NotificationDeliveryState.RETRY_WAIT)) {
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
        if (delivery.getState() == NotificationDeliveryState.RETRY_WAIT
                && delivery.getNextAttemptAt().isAfter(now)) {
            return null;
        }
        delivery.setState(NotificationDeliveryState.SENDING);
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setNextAttemptAt(null);
        delivery.setDiagnosticCode(null);
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
    public void failed(Long deliveryId, FcmDeliveryException failure) {
        NotificationDeliveryEntity delivery = deliveryRepository.findByIdForUpdate(deliveryId).orElse(null);
        if (delivery == null || delivery.getState() != NotificationDeliveryState.SENDING) {
            return;
        }
        Instant now = clock.instant();
        delivery.setNextAttemptAt(null);
        if (failure.failureKind() == FcmDeliveryException.FailureKind.INVALID_TOKEN) {
            delivery.setState(NotificationDeliveryState.INVALID_TOKEN);
            delivery.setDiagnosticCode(failure.diagnosticCode());
            delivery.getDevice().setActive(false);
            delivery.getDevice().setUpdatedAt(now);
        } else if (failure.failureKind() == FcmDeliveryException.FailureKind.PERMANENT) {
            delivery.setState(NotificationDeliveryState.FAILED);
            delivery.setDiagnosticCode(failure.diagnosticCode());
        } else {
            long multiplier = 1L << Math.max(0, delivery.getAttemptCount() - 1);
            Instant nextAttempt = now.plus(BASE_RETRY_DELAY.multipliedBy(multiplier));
            if (!nextAttempt.isBefore(delivery.getExpiresAt())) {
                delivery.setState(NotificationDeliveryState.EXPIRED);
                delivery.setDiagnosticCode("DELIVERY_EXPIRED");
            } else if (delivery.getAttemptCount() >= MAX_ATTEMPTS) {
                delivery.setState(NotificationDeliveryState.FAILED);
                delivery.setDiagnosticCode("FCM_RETRY_EXHAUSTED");
            } else {
                delivery.setState(NotificationDeliveryState.RETRY_WAIT);
                delivery.setNextAttemptAt(nextAttempt);
                delivery.setDiagnosticCode(failure.diagnosticCode());
            }
        }
        delivery.setUpdatedAt(now);
        deliveryRepository.save(delivery);
    }
}
