package com.atenea.api.mobile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterPushTokenRequest(
        @NotBlank @Size(max = 255) String expoPushToken,
        @Size(max = 190) String deviceId,
        @Size(max = 190) String deviceName,
        @NotBlank @Size(max = 32) String platform,
        @Size(max = 64) String appVersion
) {
}
