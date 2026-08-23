package com.atenea.auth;

public record SessionProtocolNegotiation(
        String version,
        boolean familyProtocol
) {

    public static SessionProtocolNegotiation resolve(
            String requestedVersion,
            Boolean singleFlightRefresh,
            String supportedVersion
    ) {
        if (supportedVersion == null
                || supportedVersion.isBlank()
                || !supportedVersion.equals(supportedVersion.trim())) {
            throw new OperatorAuthenticationException("Session protocol configuration unavailable");
        }
        boolean versionAbsent = requestedVersion == null;
        boolean capabilityAbsent = singleFlightRefresh == null;
        if (versionAbsent && capabilityAbsent) {
            return new SessionProtocolNegotiation(null, false);
        }
        if (versionAbsent
                || requestedVersion.isBlank()
                || !requestedVersion.equals(requestedVersion.trim())
                || capabilityAbsent
                || !singleFlightRefresh
                || !supportedVersion.equals(requestedVersion)) {
            return new SessionProtocolNegotiation(null, false);
        }
        return new SessionProtocolNegotiation(requestedVersion, true);
    }
}
