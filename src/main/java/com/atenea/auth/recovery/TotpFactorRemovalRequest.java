package com.atenea.auth.recovery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TotpFactorRemovalRequest(
        @NotBlank @Pattern(regexp = "[0-9]{6}") String code
) {
    @Override
    public String toString() { return "TotpFactorRemovalRequest[REDACTED]"; }
}
