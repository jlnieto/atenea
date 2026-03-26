package com.atenea.service.worksession;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentRunProgressService {

    public void applyExternalTurnId(AgentRunEntity run, String externalTurnId) {
        if (StringUtils.hasText(externalTurnId)) {
            run.setExternalTurnId(externalTurnId.trim());
        }
    }

    public void applyExternalThreadId(WorkSessionEntity session, String externalThreadId) {
        if (StringUtils.hasText(externalThreadId)) {
            session.setExternalThreadId(externalThreadId.trim());
            session.setUpdatedAt(Instant.now());
        }
    }
}
