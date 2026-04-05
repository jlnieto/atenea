package com.atenea.service.core;

import java.util.Locale;

public enum SessionSpeechMode {
    BRIEF,
    FULL;

    public static SessionSpeechMode fromQueryParam(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return BRIEF;
        }
        return switch (rawValue.trim().toLowerCase(Locale.ROOT)) {
            case "brief", "resumen", "short" -> BRIEF;
            case "full", "completa", "long" -> FULL;
            default -> throw new CoreInvalidContextException("Unsupported session speech mode: " + rawValue);
        };
    }
}
