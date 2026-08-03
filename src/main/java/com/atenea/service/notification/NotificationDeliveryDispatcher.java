package com.atenea.service.notification;

import com.atenea.codexoperations.CodexSessionOperationsProperties;
import com.atenea.mobilepush.FcmPushSender;
import com.atenea.mobilepush.FcmDeliveryException;
import java.time.Clock;
import com.atenea.persistence.notification.NotificationDeliveryRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class NotificationDeliveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryDispatcher.class);
    private static final int BATCH_SIZE = 25;

    private final CodexSessionOperationsProperties properties;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationDeliveryClaimService claimService;
    private final FcmPushSender fcmPushSender;
    private final Clock clock;

    @Autowired
    public NotificationDeliveryDispatcher(
            CodexSessionOperationsProperties properties,
            NotificationDeliveryRepository deliveryRepository,
            NotificationDeliveryClaimService claimService,
            FcmPushSender fcmPushSender) {
        this(properties, deliveryRepository, claimService, fcmPushSender, Clock.systemUTC());
    }

    NotificationDeliveryDispatcher(
            CodexSessionOperationsProperties properties,
            NotificationDeliveryRepository deliveryRepository,
            NotificationDeliveryClaimService claimService,
            FcmPushSender fcmPushSender,
            Clock clock) {
        this.properties = properties;
        this.deliveryRepository = deliveryRepository;
        this.claimService = claimService;
        this.fcmPushSender = fcmPushSender;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ATENEA_NOTIFICATION_DISPATCH_DELAY_MS:5000}")
    public void dispatchPending() {
        if (!properties.isNotificationOutboxEnabled() || !fcmPushSender.isReady()) {
            return;
        }
        List<Long> ids = deliveryRepository.findDispatchableIds(clock.instant(), PageRequest.of(0, BATCH_SIZE));
        for (Long id : ids) {
            NotificationDeliveryCommand command = claimService.claim(id);
            if (command == null) {
                continue;
            }
            try {
                fcmPushSender.send(List.of(new FcmPushSender.FcmPushMessage(
                        command.pushToken(), command.title(), command.body(), command.data())));
                claimService.delivered(command.deliveryId());
            } catch (FcmDeliveryException exception) {
                claimService.failed(command.deliveryId(), exception);
                log.warn("Generic notification delivery failed deliveryId={}", command.deliveryId());
            } catch (RuntimeException exception) {
                claimService.failed(command.deliveryId(), new FcmDeliveryException(
                        FcmDeliveryException.FailureKind.RETRYABLE,
                        "FCM_TRANSPORT_ERROR",
                        exception));
                log.warn("Generic notification delivery failed deliveryId={}", command.deliveryId());
            }
        }
    }
}
