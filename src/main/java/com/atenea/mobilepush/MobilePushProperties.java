package com.atenea.mobilepush;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atenea.mobile-push")
public class MobilePushProperties {

    private boolean enabled;
    private String fcmProjectId;
    private String fcmClientEmail;
    private String fcmPrivateKey;
    private String fcmPrivateKeyId;
    private URI fcmTokenUrl = URI.create("https://oauth2.googleapis.com/token");
    private URI fcmApiBaseUrl = URI.create("https://fcm.googleapis.com");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFcmProjectId() {
        return fcmProjectId;
    }

    public void setFcmProjectId(String fcmProjectId) {
        this.fcmProjectId = fcmProjectId;
    }

    public String getFcmClientEmail() {
        return fcmClientEmail;
    }

    public void setFcmClientEmail(String fcmClientEmail) {
        this.fcmClientEmail = fcmClientEmail;
    }

    public String getFcmPrivateKey() {
        return fcmPrivateKey;
    }

    public void setFcmPrivateKey(String fcmPrivateKey) {
        this.fcmPrivateKey = fcmPrivateKey;
    }

    public String getFcmPrivateKeyId() {
        return fcmPrivateKeyId;
    }

    public void setFcmPrivateKeyId(String fcmPrivateKeyId) {
        this.fcmPrivateKeyId = fcmPrivateKeyId;
    }

    public URI getFcmTokenUrl() {
        return fcmTokenUrl;
    }

    public void setFcmTokenUrl(URI fcmTokenUrl) {
        this.fcmTokenUrl = fcmTokenUrl;
    }

    public URI getFcmApiBaseUrl() {
        return fcmApiBaseUrl;
    }

    public void setFcmApiBaseUrl(URI fcmApiBaseUrl) {
        this.fcmApiBaseUrl = fcmApiBaseUrl;
    }
}
