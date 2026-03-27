package com.atenea.api.worksession;

public record ResolveWorkSessionResponse(
        boolean created,
        WorkSessionResponse session
) {
}
