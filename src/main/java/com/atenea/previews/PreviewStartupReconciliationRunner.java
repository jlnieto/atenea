package com.atenea.previews;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!retained-draft-recovery-command")
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
