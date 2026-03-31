package com.atenea.api.core;

public record CoreClarificationOptionResponse(
        String type,
        Long targetId,
        String label
) {
}
