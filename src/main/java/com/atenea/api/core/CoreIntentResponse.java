package com.atenea.api.core;

import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreRiskLevel;
import java.math.BigDecimal;

public record CoreIntentResponse(
        String intent,
        CoreDomain domain,
        String capability,
        CoreRiskLevel riskLevel,
        boolean requiresConfirmation,
        BigDecimal confidence
) {
}
