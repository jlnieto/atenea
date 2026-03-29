package com.atenea.service.worksession;

public class SessionDeliverableNotFoundException extends RuntimeException {

    public SessionDeliverableNotFoundException(Long sessionId, Long deliverableId) {
        super("SessionDeliverable with id '%s' was not found for WorkSession '%s'"
                .formatted(deliverableId, sessionId));
    }
}
