package com.atenea.api.mobile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UnregisterPushTokenRequest(
        @NotBlank @Size(max = 255) String expoPushToken
) {
}
