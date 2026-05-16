package com.atenea.service.operations;

public class OperationsIncidentNotFoundException extends RuntimeException {

    public OperationsIncidentNotFoundException(Long incidentId) {
        super("Operations incident not found: " + incidentId);
    }
}
