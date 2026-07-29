package com.atenea.previews;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class PreviewStartupReconciliationRunner implements ApplicationRunner {

    private final PreviewReconciliationService reconciliationService;

    public PreviewStartupReconciliationRunner(PreviewReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        reconciliationService.reconcilePersisted();
    }
}
