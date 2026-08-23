package com.atenea.auth.recovery;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AccountRecoveryRequest(
        @NotBlank @Email @Size(max = 190) String email,
        @NotBlank @Size(max = 512) String password,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{22}") String recoveryCode
) {
    @Override
    public String toString() { return "AccountRecoveryRequest[REDACTED]"; }
}
