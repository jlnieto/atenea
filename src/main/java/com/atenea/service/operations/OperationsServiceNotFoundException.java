package com.atenea.service.operations;

public class OperationsServiceNotFoundException extends RuntimeException {

    public OperationsServiceNotFoundException(Long hostId, String serviceName) {
        super("Managed service '" + serviceName + "' was not found for host '" + hostId + "'");
    }
}
