package com.atenea.auth.recovery;

import java.util.List;

public record TotpEnrollmentActivationResponse(List<String> recoveryCodes) {
    public TotpEnrollmentActivationResponse {
        recoveryCodes = List.copyOf(recoveryCodes);
    }

    @Override
    public String toString() { return "TotpEnrollmentActivationResponse[REDACTED]"; }
}
