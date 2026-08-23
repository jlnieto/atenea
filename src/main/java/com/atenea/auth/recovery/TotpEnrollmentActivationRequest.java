package com.atenea.auth.recovery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record TotpEnrollmentActivationRequest(
        @NotNull UUID enrollmentId,
        @NotBlank @Pattern(regexp = "[0-9]{6}") String code
) {
    @Override
    public String toString() { return "TotpEnrollmentActivationRequest[REDACTED]"; }
}
