package com.atenea.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atenea.auth")
public class OperatorAuthProperties {

    private final Jwt jwt = new Jwt();
    private final Bootstrap bootstrap = new Bootstrap();
    private final Sessions sessions = new Sessions();
    private final WebAuthn webAuthn = new WebAuthn();
    private final Recovery recovery = new Recovery();
    private final PrivilegedActions privilegedActions = new PrivilegedActions();

    public Jwt getJwt() {
        return jwt;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public Sessions getSessions() {
        return sessions;
    }

    public WebAuthn getWebAuthn() {
        return webAuthn;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    public PrivilegedActions getPrivilegedActions() { return privilegedActions; }

    public static class Jwt {

        private String issuer;
        private String secret;
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofDays(30);

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }
    }

    public static class Bootstrap {

        private boolean enabled;
        private String email;
        private String password;
        private String displayName = "Default Operator";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }

    public static class Sessions {

        private boolean enforcementEnabled;
        private String defaultClientType = "MOBILE";
        private String defaultDeviceLabel = "Atenea mobile";
        private String supportedProtocolVersion = "FAMILY_V1";
        private String webOrigin = "https://atenea.yudri.es";

        public boolean isEnforcementEnabled() {
            return enforcementEnabled;
        }

        public void setEnforcementEnabled(boolean value) {
            enforcementEnabled = value;
        }

        public String getDefaultClientType() {
            return defaultClientType;
        }

        public void setDefaultClientType(String value) {
            defaultClientType = value;
        }

        public String getDefaultDeviceLabel() {
            return defaultDeviceLabel;
        }

        public void setDefaultDeviceLabel(String value) {
            defaultDeviceLabel = value;
        }

        public String getSupportedProtocolVersion() {
            return supportedProtocolVersion;
        }

        public void setSupportedProtocolVersion(String value) {
            supportedProtocolVersion = value;
        }

        public String getWebOrigin() {
            return webOrigin;
        }

        public void setWebOrigin(String value) {
            webOrigin = value;
        }
    }

    public static class WebAuthn {

        private boolean enabled;
        private String relyingPartyId = "atenea.yudri.es";
        private String relyingPartyName = "Atenea";
        private String webOrigin = "https://atenea.yudri.es";
        private List<String> androidOrigins = new ArrayList<>();
        private Duration challengeTtl = Duration.ofMinutes(5);
        private boolean credentialLifecycleEnabled;
        private boolean credentialInventoryEnabled;
        private boolean credentialSignallingEnabled;
        private boolean restrictedCeremoniesEnabled;
        private boolean controlledResetEnabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public String getRelyingPartyId() {
            return relyingPartyId;
        }

        public void setRelyingPartyId(String value) {
            relyingPartyId = value;
        }

        public String getRelyingPartyName() {
            return relyingPartyName;
        }

        public void setRelyingPartyName(String value) {
            relyingPartyName = value;
        }

        public String getWebOrigin() {
            return webOrigin;
        }

        public void setWebOrigin(String value) {
            webOrigin = value;
        }

        public List<String> getAndroidOrigins() {
            return androidOrigins;
        }

        public void setAndroidOrigins(List<String> value) {
            androidOrigins = value == null ? new ArrayList<>() : new ArrayList<>(value);
        }

        public Duration getChallengeTtl() {
            return challengeTtl;
        }

        public void setChallengeTtl(Duration value) {
            challengeTtl = value;
        }

        public boolean isCredentialLifecycleEnabled() {
            return credentialLifecycleEnabled;
        }

        public void setCredentialLifecycleEnabled(boolean value) {
            credentialLifecycleEnabled = value;
        }

        public boolean isCredentialInventoryEnabled() {
            return credentialInventoryEnabled;
        }

        public void setCredentialInventoryEnabled(boolean value) {
            credentialInventoryEnabled = value;
        }

        public boolean isCredentialSignallingEnabled() {
            return credentialSignallingEnabled;
        }

        public void setCredentialSignallingEnabled(boolean value) {
            credentialSignallingEnabled = value;
        }

        public boolean isRestrictedCeremoniesEnabled() {
            return restrictedCeremoniesEnabled;
        }

        public void setRestrictedCeremoniesEnabled(boolean value) {
            restrictedCeremoniesEnabled = value;
        }

        public boolean isControlledResetEnabled() {
            return controlledResetEnabled;
        }

        public void setControlledResetEnabled(boolean value) {
            controlledResetEnabled = value;
        }
    }

    public static class Recovery {

        private boolean totpEnabled;
        private boolean recoveryEnabled;
        private boolean enforcementEnabled;
        private String activeEncryptionKeyVersion;
        private List<String> encryptionKeys = new ArrayList<>();
        private String activeHmacKeyVersion;
        private List<String> hmacKeys = new ArrayList<>();
        private Duration enrollmentTtl = Duration.ofMinutes(10);
        private Duration attemptWindow = Duration.ofMinutes(5);
        private Duration lockout = Duration.ofMinutes(15);
        private int maxAttempts = 5;

        public boolean isTotpEnabled() { return totpEnabled; }
        public void setTotpEnabled(boolean value) { totpEnabled = value; }
        public boolean isRecoveryEnabled() { return recoveryEnabled; }
        public void setRecoveryEnabled(boolean value) { recoveryEnabled = value; }
        public boolean isEnforcementEnabled() { return enforcementEnabled; }
        public void setEnforcementEnabled(boolean value) { enforcementEnabled = value; }
        public String getActiveEncryptionKeyVersion() { return activeEncryptionKeyVersion; }
        public void setActiveEncryptionKeyVersion(String value) { activeEncryptionKeyVersion = value; }
        public List<String> getEncryptionKeys() { return new ArrayList<>(encryptionKeys); }
        public void setEncryptionKeys(List<String> value) {
            encryptionKeys = value == null ? new ArrayList<>() : new ArrayList<>(value);
        }
        public String getActiveHmacKeyVersion() { return activeHmacKeyVersion; }
        public void setActiveHmacKeyVersion(String value) { activeHmacKeyVersion = value; }
        public List<String> getHmacKeys() { return new ArrayList<>(hmacKeys); }
        public void setHmacKeys(List<String> value) {
            hmacKeys = value == null ? new ArrayList<>() : new ArrayList<>(value);
        }
        public Duration getEnrollmentTtl() { return enrollmentTtl; }
        public void setEnrollmentTtl(Duration value) { enrollmentTtl = value; }
        public Duration getAttemptWindow() { return attemptWindow; }
        public void setAttemptWindow(Duration value) { attemptWindow = value; }
        public Duration getLockout() { return lockout; }
        public void setLockout(Duration value) { lockout = value; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { maxAttempts = value; }
    }

    public static class PrivilegedActions {
        private boolean enabled;
        private boolean enforcementEnabled;
        private Duration authorizationTtl = Duration.ofMinutes(5);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public boolean isEnforcementEnabled() { return enforcementEnabled; }
        public void setEnforcementEnabled(boolean value) { enforcementEnabled = value; }
        public Duration getAuthorizationTtl() { return authorizationTtl; }
        public void setAuthorizationTtl(Duration value) { authorizationTtl = value; }
    }
}
