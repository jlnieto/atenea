package com.atenea.service.worksession;

import com.atenea.persistence.worksession.ValidationOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Collects existing durable results even when the mobile conversation is closed.
 * It never admits the next validation or retries a failed one.
 */
@Component
public class ValidationResultReconciler {
    private static final Logger log = LoggerFactory.getLogger(ValidationResultReconciler.class);
    private final ValidationOperationRepository operations;
    private final ClosedValidationOperationService validation;

    public ValidationResultReconciler(ValidationOperationRepository operations,
            ClosedValidationOperationService validation) {
        this.operations = operations;
        this.validation = validation;
    }

    @Scheduled(scheduler = "validationResultScheduler", fixedDelay = 5000)
    public void reconcilePending() {
        for (var operation : operations.pendingResults(PageRequest.of(0, 10))) {
            try {
                // The proxied service owns one transaction per existing operation.
                validation.reconcileRetainedResult(operation.getSessionId(), operation.getId());
            } catch (RuntimeException unavailable) {
                // Never fabricate a terminal result or expose worker messages/credentials.
                log.debug("Retained validation {} needs a later durable observation", operation.getId());
            }
        }
    }
}
