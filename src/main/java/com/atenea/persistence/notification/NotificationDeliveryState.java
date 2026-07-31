package com.atenea.persistence.notification;

public enum NotificationDeliveryState {
    PENDING,
    SENDING,
    RETRY_WAIT,
    DELIVERED,
    EXPIRED,
    INVALID_TOKEN,
    FAILED
}
