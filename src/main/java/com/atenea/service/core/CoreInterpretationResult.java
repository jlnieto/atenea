package com.atenea.service.core;

import com.atenea.persistence.core.CoreInterpreterSource;

public record CoreInterpretationResult(
        CoreIntentProposal proposal,
        CoreInterpreterSource source,
        String detail
) {
}
