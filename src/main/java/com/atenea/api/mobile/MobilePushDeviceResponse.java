package com.atenea.api.mobile;

import java.time.Instant;

public record MobilePushDeviceResponse(
        Long id,
        String expoPushToken,
        String deviceId,
        String deviceName,
        String platform,
        String appVersion,
        boolean active,
        Instant lastRegisteredAt,
        Instant updatedAt
) {
}
