package com.atenea.persistence.worksession;

public enum CodexReasoningEffort {
    NONE("none"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    private final String canonicalValue;

    CodexReasoningEffort(String canonicalValue) {
        this.canonicalValue = canonicalValue;
    }

    public String canonicalValue() {
        return canonicalValue;
    }

    public static CodexReasoningEffort fromCanonicalValue(String value) {
        for (CodexReasoningEffort effort : values()) {
            if (effort.canonicalValue.equals(value)) {
                return effort;
            }
        }
        throw new IllegalArgumentException("Unsupported Codex reasoning effort");
    }
}
