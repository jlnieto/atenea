package com.atenea.service.verification;

public record ProjectVerificationTestResult(
        String name,
        String status,
        Integer exitCode,
        Long durationMillis,
        String summary
) {
}
