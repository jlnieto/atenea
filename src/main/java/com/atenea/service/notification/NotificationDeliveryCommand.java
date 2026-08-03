package com.atenea.service.notification;

import java.util.Map;

public record NotificationDeliveryCommand(
        Long deliveryId,
        String pushToken,
        String title,
        String body,
        Map<String, Object> data
) {}
