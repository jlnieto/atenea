package com.atenea.api.mobile;

import jakarta.validation.constraints.Size;

public record CreateMobileVoiceNoteSendIntentRequest(
        @Size(max = 500) String instruction
) {
}
