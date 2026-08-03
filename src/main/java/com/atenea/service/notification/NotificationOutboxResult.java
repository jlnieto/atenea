package com.atenea.service.notification;

import com.atenea.persistence.notification.NotificationEventEntity;

public record NotificationOutboxResult(
        NotificationEventEntity event,
        boolean created,
        int deliveryCount) {
}
