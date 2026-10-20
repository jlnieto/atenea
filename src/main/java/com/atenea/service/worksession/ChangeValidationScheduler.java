package com.atenea.service.worksession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ChangeValidationScheduler {
    private static final Logger log=LoggerFactory.getLogger(ChangeValidationScheduler.class);
    private final ChangeValidationWorkflowStore store;
    private final ChangeValidationCoordinator coordinator;
    public ChangeValidationScheduler(ChangeValidationWorkflowStore store,ChangeValidationCoordinator coordinator) {
        this.store=store;this.coordinator=coordinator;
    }
    @Scheduled(scheduler="changeValidationTaskScheduler",fixedDelay=3000)
    public void progress() {
        for (var id:store.pending()) {
            try { coordinator.progress(id); }
            catch (RuntimeException unavailable) {
                log.debug("Validation workflow {} awaits its next durable observation",id);
            }
        }
    }
}
