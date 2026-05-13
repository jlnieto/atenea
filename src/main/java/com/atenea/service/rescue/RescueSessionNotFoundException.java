package com.atenea.service.rescue;

public class RescueSessionNotFoundException extends RuntimeException {

    public RescueSessionNotFoundException(Long rescueSessionId) {
        super("Rescue session with id '" + rescueSessionId + "' was not found");
    }
}
