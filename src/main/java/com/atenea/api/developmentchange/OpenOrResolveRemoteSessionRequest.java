package com.atenea.api.developmentchange;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashSet;
import java.util.Set;

public final class OpenOrResolveRemoteSessionRequest {

    private Long expectedChangeRevision;
    private final Set<String> unsupportedFields = new LinkedHashSet<>();

    public OpenOrResolveRemoteSessionRequest() {
    }

    public OpenOrResolveRemoteSessionRequest(long expectedChangeRevision) {
        this.expectedChangeRevision = expectedChangeRevision;
    }

    public Long getExpectedChangeRevision() {
        return expectedChangeRevision;
    }

    public void setExpectedChangeRevision(Long value) {
        expectedChangeRevision = value;
    }

    @JsonAnySetter
    public void rejectUnsupportedField(String name, Object ignoredValue) {
        unsupportedFields.add(name);
    }

    public Set<String> unsupportedFields() {
        return Set.copyOf(unsupportedFields);
    }
}
