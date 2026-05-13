package com.atenea.service.rescue;

public class RescueSessionAlreadyRunningException extends RuntimeException {

    public RescueSessionAlreadyRunningException(Long rescueSessionId) {
        super("Rescue session with id '" + rescueSessionId + "' is already RUNNING");
    }
}
