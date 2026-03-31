package com.atenea.service.core;

import com.atenea.persistence.core.CoreDomain;
import java.math.BigDecimal;
import java.util.Map;

public record CoreIntentProposal(
        String intent,
        CoreDomain domain,
        String capability,
        Map<String, Object> parameters,
        BigDecimal confidence
) {
}
