package com.atenea.service.mobile;

import com.atenea.api.mobile.MobileSessionReadStateResponse;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.auth.OperatorSessionReadStateEntity;
import com.atenea.persistence.auth.OperatorSessionReadStateRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MobileSessionReadStateService {

    private final OperatorRepository operatorRepository;
    private final OperatorSessionReadStateRepository operatorSessionReadStateRepository;
    private final WorkSessionRepository workSessionRepository;

    public MobileSessionReadStateService(
            OperatorRepository operatorRepository,
            OperatorSessionReadStateRepository operatorSessionReadStateRepository,
            WorkSessionRepository workSessionRepository
    ) {
        this.operatorRepository = operatorRepository;
        this.operatorSessionReadStateRepository = operatorSessionReadStateRepository;
        this.workSessionRepository = workSessionRepository;
    }

    @Transactional(readOnly = true)
    public List<MobileSessionReadStateResponse> getReadStates(AuthenticatedOperator authenticatedOperator) {
        ensureActiveOperator(authenticatedOperator);
        return operatorSessionReadStateRepository.findByOperatorIdOrderByUpdatedAtDesc(authenticatedOperator.operatorId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public MobileSessionReadStateResponse markRead(AuthenticatedOperator authenticatedOperator, Long sessionId) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        WorkSessionEntity session = workSessionRepository.findById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));

        Instant now = Instant.now();
        OperatorSessionReadStateEntity entity = operatorSessionReadStateRepository
                .findByOperatorIdAndWorkSessionId(operator.getId(), sessionId)
                .orElseGet(OperatorSessionReadStateEntity::new);

        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setOperator(operator);
        entity.setWorkSession(session);
        entity.setLastSeenActivityAt(session.getLastActivityAt());
        entity.setUpdatedAt(now);
        return toResponse(operatorSessionReadStateRepository.save(entity));
    }

    private OperatorEntity ensureActiveOperator(AuthenticatedOperator authenticatedOperator) {
        return operatorRepository.findById(authenticatedOperator.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(() -> new OperatorAuthenticationException("Operator account not found"));
    }

    private MobileSessionReadStateResponse toResponse(OperatorSessionReadStateEntity entity) {
        return new MobileSessionReadStateResponse(
                entity.getWorkSession().getId(),
                entity.getLastSeenActivityAt(),
                entity.getUpdatedAt()
        );
    }
}
