package com.atenea.service.core;

import java.util.List;

public record SessionSpeechPreparationResult(
        String text,
        SessionSpeechMode mode,
        boolean truncated,
        List<String> sectionsUsed
) {
}
