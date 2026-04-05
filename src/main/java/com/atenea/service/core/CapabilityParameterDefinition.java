package com.atenea.service.core;

public record CapabilityParameterDefinition(
        String name,
        String type,
        boolean required,
        String description
) {
}
