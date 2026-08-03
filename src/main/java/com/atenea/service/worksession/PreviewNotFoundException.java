package com.atenea.service.worksession;

import java.util.UUID;

public class PreviewNotFoundException extends RuntimeException {

    public PreviewNotFoundException(UUID previewId) {
        super("El preview no existe en esta WorkSession: " + previewId);
    }
}
