package com.atenea.api.core;

import com.atenea.persistence.core.CoreCommandStatus;

public record CoreCommandResponse(
        Long commandId,
        CoreCommandStatus status,
        CoreInterpretationResponse interpretation,
        CoreIntentResponse intent,
        CoreCommandResultResponse result,
        CoreClarificationResponse clarification,
        CoreConfirmationResponse confirmation,
        String operatorMessage,
        String speakableMessage
) {
}
