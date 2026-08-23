package com.atenea.codexoperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.remoteworker.RemoteWorkerProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class LegacyRemoteCloseServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final OperatorRepository operatorRepository = mock(OperatorRepository.class);
    private final WorkSessionRepository sessionRepository = mock(WorkSessionRepository.class);
    private final LegacyRemoteCloseService service = new LegacyRemoteCloseService(
            new RemoteWorkerProperties(), jdbcTemplate, operatorRepository, sessionRepository);
    private final AuthenticatedOperator operator =
            new AuthenticatedOperator(1L, "operator@atenea.test", "Operator");

    @Test
    void defaultOffPlanDoesNotInspectSessionsOrPersistAnything() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.createPlan(operator, 16L,
                        new LegacyRemoteCloseService.LegacyRemoteClosePlanRequest(
                                "RECONCILE_REMOTE_CLOSE", UUID.randomUUID())));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
        verifyNoInteractions(jdbcTemplate, operatorRepository, sessionRepository);
    }

    @Test
    void defaultOffConfirmationDoesNotConsumeOrMutateAnything() {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.confirm(operator, 16L,
                        new LegacyRemoteCloseService.LegacyRemoteCloseConfirmationRequest(
                                "RECONCILE_REMOTE_CLOSE", UUID.randomUUID(),
                                "a".repeat(64), UUID.randomUUID())));

        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
        verifyNoInteractions(jdbcTemplate, operatorRepository, sessionRepository);
    }
}
