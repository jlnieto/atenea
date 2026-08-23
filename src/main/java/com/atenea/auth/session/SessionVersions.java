package com.atenea.auth.session;

public record SessionVersions(
        long credentialVersion,
        long roleVersion
) {

    public SessionVersions {
        if (credentialVersion < 0 || roleVersion < 0) {
            throw new IllegalArgumentException("Session versions must not be negative");
        }
    }

    public boolean matches(long currentCredentialVersion, long currentRoleVersion) {
        return credentialVersion == currentCredentialVersion && roleVersion == currentRoleVersion;
    }
}
