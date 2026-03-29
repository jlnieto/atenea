package com.atenea.service.worksession;

public class ApprovedPriceEstimateNotFoundException extends RuntimeException {

    public ApprovedPriceEstimateNotFoundException(Long sessionId) {
        super("Approved PRICE_ESTIMATE was not found for WorkSession '%s'".formatted(sessionId));
    }
}
