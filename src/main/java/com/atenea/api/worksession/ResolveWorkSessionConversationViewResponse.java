package com.atenea.api.worksession;

public record ResolveWorkSessionConversationViewResponse(
        boolean created,
        WorkSessionConversationViewResponse view
) {
}
