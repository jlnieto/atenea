package com.atenea.api.core;

import com.atenea.persistence.core.CoreInterpreterSource;

public record CoreInterpretationResponse(
        CoreInterpreterSource source,
        String detail
) {
}
