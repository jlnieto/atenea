package com.atenea.api.rescue;

import java.util.List;

public record RescueSessionConversationViewResponse(
        RescueSessionResponse session,
        List<RescueSessionTurnResponse> turns
) {
}
