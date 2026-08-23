package com.atenea.api.developmentchange;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CreateDevelopmentChangeRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    private final Set<String> unsupportedFields = new LinkedHashSet<>();

    public CreateDevelopmentChangeRequest() {
    }

    public CreateDevelopmentChangeRequest(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String value) {
        title = value;
    }

    @JsonAnySetter
    public void rejectUnsupportedField(String name, Object ignoredValue) {
        unsupportedFields.add(name);
    }

    public Set<String> unsupportedFields() {
        return Set.copyOf(unsupportedFields);
    }
}
