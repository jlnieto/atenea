package com.atenea.codexoperations;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "atenea.codex-session-operations")
public class CodexSessionOperationsProperties {
    private boolean profilesEnabled;
    private boolean progressEnabled;
    private boolean recoveryEnabled;
    private boolean notificationOutboxEnabled;
    private boolean managedUpdatesEnabled;

    public boolean isProfilesEnabled() { return profilesEnabled; }
    public void setProfilesEnabled(boolean value) { profilesEnabled = value; }
    public boolean isProgressEnabled() { return progressEnabled; }
    public void setProgressEnabled(boolean value) { progressEnabled = value; }
    public boolean isRecoveryEnabled() { return recoveryEnabled; }
    public void setRecoveryEnabled(boolean value) { recoveryEnabled = value; }
    public boolean isNotificationOutboxEnabled() { return notificationOutboxEnabled; }
    public void setNotificationOutboxEnabled(boolean value) { notificationOutboxEnabled = value; }
    public boolean isManagedUpdatesEnabled() { return managedUpdatesEnabled; }
    public void setManagedUpdatesEnabled(boolean value) { managedUpdatesEnabled = value; }
}
