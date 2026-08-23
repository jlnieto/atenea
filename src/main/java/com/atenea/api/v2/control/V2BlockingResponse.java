package com.atenea.api.v2.control;

import com.atenea.v2.control.V2FailureCategory;
import java.util.regex.Pattern;

public record V2BlockingResponse(
        V2FailureCategory category,
        String code,
        String message) {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,79}$");

    public V2BlockingResponse {
        if (category == null) {
            throw new IllegalArgumentException("Blocking category is required");
        }
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Blocking code must be a closed symbolic identifier");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Blocking message is required");
        }
    }
}
