package com.atenea.api.mobile;

import jakarta.validation.constraints.NotBlank;

public record CreateMobileVoiceNoteRequest(
        @NotBlank String text
) {
}
