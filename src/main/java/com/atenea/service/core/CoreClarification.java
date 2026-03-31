package com.atenea.service.core;

import java.util.List;

public record CoreClarification(
        String message,
        List<CoreClarificationOption> options
) {
}
