package com.atenea.developmentchange;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "atenea.remote-work-beta")
public class RemoteWorkBetaProperties {

    private boolean openOrResolveEnabled;
    private boolean recoveryEnabled;

    public boolean isOpenOrResolveEnabled() {
        return openOrResolveEnabled;
    }

    public void setOpenOrResolveEnabled(boolean value) {
        openOrResolveEnabled = value;
    }

    public boolean isRecoveryEnabled() {
        return recoveryEnabled;
    }

    public void setRecoveryEnabled(boolean value) {
        recoveryEnabled = value;
    }
}
