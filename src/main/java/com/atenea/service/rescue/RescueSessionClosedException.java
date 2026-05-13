package com.atenea.service.rescue;

import com.atenea.persistence.rescue.RescueSessionStatus;

public class RescueSessionClosedException extends RuntimeException {

    public RescueSessionClosedException(Long rescueSessionId, RescueSessionStatus status) {
        super("Rescue session with id '" + rescueSessionId + "' is not open; current status is " + status);
    }
}
