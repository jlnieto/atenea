package com.atenea.persistence.worksession;

import java.time.Duration;

public enum AttachmentRetentionClass {
    TRANSIENT(Duration.ofHours(24)),
    SESSION(Duration.ofDays(30)),
    EVIDENCE(Duration.ofDays(180));

    private final Duration duration;

    AttachmentRetentionClass(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }
}
