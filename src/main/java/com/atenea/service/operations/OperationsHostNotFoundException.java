package com.atenea.service.operations;

public class OperationsHostNotFoundException extends RuntimeException {

    public OperationsHostNotFoundException(Long hostId) {
        super("Managed host with id '" + hostId + "' was not found");
    }
}
