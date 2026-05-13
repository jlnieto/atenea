package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MobileSessionReadStateServiceTest {

    @Mock
    private OperatorRepository operatorRepository;

    @Mock
    private OperatorSessionReadStateRepository operatorSessionReadStateRepository;

    @Mock
    private WorkSessionRepository workSessionRepository;

    private MobileSessionReadStateService mobileSessionReadStateService;

    @BeforeEach
    void setUp() {
        mobileSessionReadStateService = new MobileSessionReadStateService(
                operatorRepository,
                operatorSessionReadStateRepository,
                workSessionRepository);
    }

    @Test
    void getReadStatesReturnsPersistedRowsForActiveOperator() {
        AuthenticatedOperator authenticatedOperator = new AuthenticatedOperator(4L, "operator@atenea.local", "Operator");
        OperatorEntity operator = activeOperator(4L);
        OperatorSessionReadStateEntity entity = persistedReadState(operator, session(12L, Instant.parse("2026-03-29T10:00:00Z")));
        entity.setLastSeenActivityAt(Instant.parse("2026-03-29T10:05:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-03-29T10:06:00Z"));

        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(operatorSessionReadStateRepository.findByOperatorIdOrderByUpdatedAtDesc(4L)).thenReturn(List.of(entity));

        List<MobileSessionReadStateResponse> response = mobileSessionReadStateService.getReadStates(authenticatedOperator);

        assertEquals(1, response.size());
        assertEquals(12L, response.get(0).sessionId());
        assertEquals(Instant.parse("2026-03-29T10:05:00Z"), response.get(0).lastSeenActivityAt());
    }

    @Test
    void markReadUpsertsSessionActivityForActiveOperator() {
        AuthenticatedOperator authenticatedOperator = new AuthenticatedOperator(4L, "operator@atenea.local", "Operator");
        OperatorEntity operator = activeOperator(4L);
        WorkSessionEntity session = session(12L, Instant.parse("2026-03-29T10:10:00Z"));
        OperatorSessionReadStateEntity existing = persistedReadState(operator, session);

        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(workSessionRepository.findById(12L)).thenReturn(Optional.of(session));
        when(operatorSessionReadStateRepository.findByOperatorIdAndWorkSessionId(4L, 12L)).thenReturn(Optional.of(existing));
        when(operatorSessionReadStateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MobileSessionReadStateResponse response = mobileSessionReadStateService.markRead(authenticatedOperator, 12L);

        ArgumentCaptor<OperatorSessionReadStateEntity> captor = ArgumentCaptor.forClass(OperatorSessionReadStateEntity.class);
        verify(operatorSessionReadStateRepository).save(captor.capture());
        OperatorSessionReadStateEntity saved = captor.getValue();

        assertEquals(operator, saved.getOperator());
        assertEquals(session, saved.getWorkSession());
        assertEquals(session.getLastActivityAt(), saved.getLastSeenActivityAt());
        assertEquals(12L, response.sessionId());
        assertEquals(session.getLastActivityAt(), response.lastSeenActivityAt());
    }

    @Test
    void markReadRejectsUnknownOrInactiveOperator() {
        AuthenticatedOperator authenticatedOperator = new AuthenticatedOperator(4L, "operator@atenea.local", "Operator");
        when(operatorRepository.findById(4L)).thenReturn(Optional.empty());

        assertThrows(
                OperatorAuthenticationException.class,
                () -> mobileSessionReadStateService.markRead(authenticatedOperator, 12L));
    }

    @Test
    void markReadRejectsUnknownSession() {
        AuthenticatedOperator authenticatedOperator = new AuthenticatedOperator(4L, "operator@atenea.local", "Operator");
        OperatorEntity operator = activeOperator(4L);
        when(operatorRepository.findById(4L)).thenReturn(Optional.of(operator));
        when(workSessionRepository.findById(12L)).thenReturn(Optional.empty());

        assertThrows(
                WorkSessionNotFoundException.class,
                () -> mobileSessionReadStateService.markRead(authenticatedOperator, 12L));
    }

    private OperatorEntity activeOperator(Long id) {
        OperatorEntity operator = new OperatorEntity();
        operator.setId(id);
        operator.setEmail("operator@atenea.local");
        operator.setDisplayName("Operator");
        operator.setActive(true);
        operator.setCreatedAt(Instant.parse("2026-03-29T09:00:00Z"));
        operator.setUpdatedAt(Instant.parse("2026-03-29T09:00:00Z"));
        return operator;
    }

    private WorkSessionEntity session(Long id, Instant lastActivityAt) {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(id);
        session.setLastActivityAt(lastActivityAt);
        return session;
    }

    private OperatorSessionReadStateEntity persistedReadState(OperatorEntity operator, WorkSessionEntity session) {
        OperatorSessionReadStateEntity entity = new OperatorSessionReadStateEntity();
        entity.setId(30L);
        entity.setOperator(operator);
        entity.setWorkSession(session);
        entity.setLastSeenActivityAt(Instant.parse("2026-03-29T10:00:00Z"));
        entity.setCreatedAt(Instant.parse("2026-03-29T10:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-03-29T10:00:00Z"));
        return entity;
    }
}
