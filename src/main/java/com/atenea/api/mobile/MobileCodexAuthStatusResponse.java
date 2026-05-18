package com.atenea.api.mobile;

public record MobileCodexAuthStatusResponse(
        String server,
        boolean configured,
        boolean compliant,
        String status,
        String requiredAuthMode,
        String authMode,
        boolean apiKeyPresent,
        boolean tokensPresent
) {
}
