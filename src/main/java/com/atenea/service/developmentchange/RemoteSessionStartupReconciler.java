package com.atenea.service.developmentchange;

import com.atenea.developmentchange.RemoteWorkBetaProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(120)
public class RemoteSessionStartupReconciler implements ApplicationRunner {

    private final RemoteWorkBetaProperties properties;
    private final RemoteSessionService service;

    public RemoteSessionStartupReconciler(
            RemoteWorkBetaProperties properties,
            RemoteSessionService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.isRecoveryEnabled()) {
            service.recoverIncompleteOperations();
        }
    }
}
