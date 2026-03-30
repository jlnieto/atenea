package com.atenea.mobilepush;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atenea.mobile-push")
public class MobilePushProperties {

    private boolean enabled;
    private URI expoPushUrl = URI.create("https://exp.host/--/api/v2/push/send");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getExpoPushUrl() {
        return expoPushUrl;
    }

    public void setExpoPushUrl(URI expoPushUrl) {
        this.expoPushUrl = expoPushUrl;
    }
}
