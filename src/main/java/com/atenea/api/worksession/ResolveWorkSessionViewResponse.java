package com.atenea.api.worksession;

public record ResolveWorkSessionViewResponse(
        boolean created,
        WorkSessionViewResponse view
) {
}
