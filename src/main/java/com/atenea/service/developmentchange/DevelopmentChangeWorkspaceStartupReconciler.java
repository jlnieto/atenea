package com.atenea.service.developmentchange;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DevelopmentChangeWorkspaceStartupReconciler implements ApplicationRunner {

    private final DevelopmentChangeWorkspaceService service;

    public DevelopmentChangeWorkspaceStartupReconciler(
            DevelopmentChangeWorkspaceService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        service.reconcilePersistedAfterStartup();
    }
}
