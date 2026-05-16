package com.atenea.api.mobile;

import jakarta.validation.constraints.Size;

public record SendMobileVoiceNotesRequest(
        @Size(max = 500) String instruction
) {
}
