package com.atenea.api.mobile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

public record CreateMobileVoiceSpeechRequest(
        @NotBlank @Size(max = 4096) String text,
        @Size(max = 40) String voice,
        @DecimalMin("0.5") @DecimalMax("2.0") Double speed
) {
}
