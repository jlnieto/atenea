package com.atenea.api.core;

import java.util.List;

public record CoreClarificationResponse(
        String message,
        List<CoreClarificationOptionResponse> options
) {
}
