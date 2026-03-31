package com.atenea.service.core;

public class CoreCommandNotFoundException extends RuntimeException {

    public CoreCommandNotFoundException(Long commandId) {
        super("Core command " + commandId + " was not found");
    }
}
