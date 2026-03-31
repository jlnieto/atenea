package com.atenea.service.core;

import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreRiskLevel;

public record CapabilityDefinition(
        CoreDomain domain,
        String capability,
        CoreRiskLevel riskLevel,
        boolean requiresConfirmation,
        boolean enabled
) {
}
