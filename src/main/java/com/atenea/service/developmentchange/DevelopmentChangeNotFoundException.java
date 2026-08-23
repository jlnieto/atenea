package com.atenea.service.developmentchange;

import java.util.UUID;

public class DevelopmentChangeNotFoundException extends RuntimeException {

    public DevelopmentChangeNotFoundException(UUID changeKey) {
        super("DevelopmentChange '" + changeKey + "' was not found in the requested project");
    }
}
