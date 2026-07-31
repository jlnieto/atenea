package com.atenea.service.worksession;

import com.atenea.persistence.worksession.AgentRunRecoveryOperationEntity;

public record AgentRunRecoveryRequestResult(
        AgentRunRecoveryOperationEntity operation,
        boolean created) {
}
