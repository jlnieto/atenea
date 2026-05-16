package com.atenea.api.operations;

public record ManagedHostResponse(
        Long id,
        String name,
        String description,
        String environment,
        boolean active
) {
}
