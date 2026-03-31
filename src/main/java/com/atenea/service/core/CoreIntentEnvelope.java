package com.atenea.service.core;

import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreRiskLevel;
import java.math.BigDecimal;
import java.util.Map;

public record CoreIntentEnvelope(
        String intent,
        CoreDomain domain,
        String capability,
        Map<String, Object> parameters,
        BigDecimal confidence,
        CoreRiskLevel riskLevel,
        boolean requiresConfirmation
) {
}
