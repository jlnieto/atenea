package com.atenea.api.rescue;

public record ResolveRescueSessionResponse(
        boolean created,
        RescueSessionConversationViewResponse view
) {
}
