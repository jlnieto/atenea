package com.atenea.service.core;

import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreRiskLevel;
import java.util.List;

public record CapabilityDefinition(
        CoreDomain domain,
        String capability,
        CoreRiskLevel riskLevel,
        boolean requiresConfirmation,
        boolean enabled,
        String summary,
        String whenToUse,
        String whenNotToUse,
        List<CapabilityParameterDefinition> parameters,
        List<CapabilityExample> examples
) {
    public CapabilityDefinition(
            CoreDomain domain,
            String capability,
            CoreRiskLevel riskLevel,
            boolean requiresConfirmation,
            boolean enabled
    ) {
        this(domain, capability, riskLevel, requiresConfirmation, enabled, null, null, null, List.of(), List.of());
    }

    public boolean requiresParameter(String name) {
        return parameters.stream()
                .anyMatch(parameter -> parameter.required() && parameter.name().equals(name));
    }
}
