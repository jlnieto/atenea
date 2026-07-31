package com.atenea.persistence.worksession;

public enum ValidationOperationKind {
    BACKEND_TEST("atenea-backend-test-v1"),
    WEB_BUILD("atenea-web-build-v1"),
    ANDROID_BUILD("atenea-android-build-v1");

    private final String definitionRevision;

    ValidationOperationKind(String definitionRevision) {
        this.definitionRevision = definitionRevision;
    }

    public String definitionRevision() {
        return definitionRevision;
    }
}
