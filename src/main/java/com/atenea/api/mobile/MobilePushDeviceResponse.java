package com.atenea.api.mobile;

import java.time.Instant;

public record MobilePushDeviceResponse(
        Long id,
        String pushToken,
        String deviceId,
        String deviceName,
        String platform,
        String appVersion,
        boolean active,
        Instant lastRegisteredAt,
        Instant updatedAt
) {
}
