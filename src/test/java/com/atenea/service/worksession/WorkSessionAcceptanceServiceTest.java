package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.WorkSessionAcceptanceState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkSessionAcceptanceServiceTest {

    private static final String TREE_A = "a".repeat(64);
    private static final String TREE_B = "b".repeat(64);
    private static final String PROJECTION = "c".repeat(64);
    private static final String REVISION = "backend-web-android-v1";

    @Mock
    private WorkSessionRepository workSessionRepository;

    private WorkSessionAcceptanceService service;
    private WorkSessionEntity session;

    @BeforeEach
    void setUp() {
        service = new WorkSessionAcceptanceService(workSessionRepository);
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        session = new WorkSessionEntity();
        session.setId(41L);
        session.setProject(project);
        session.setAcceptanceState(WorkSessionAcceptanceState.DRAFT);
        session.setUpdatedAt(Instant.parse("2026-07-30T12:00:00Z"));
        org.mockito.Mockito.lenient()
                .when(workSessionRepository.findLockedWithProjectById(41L))
                .thenReturn(Optional.of(session));
        org.mockito.Mockito.lenient()
                .when(workSessionRepository.save(any(WorkSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void exactTreeCanProgressToIntegrationReadyWithoutPublishing() {
        service.observeSourceTree(41L, TREE_A);
        service.markValidating(41L, TREE_A, PROJECTION, REVISION);
        WorkSessionAcceptanceSnapshot validated =
                service.markValidated(41L, TREE_A, PROJECTION, REVISION);
        WorkSessionAcceptanceSnapshot ready =
                service.markIntegrationReady(41L, TREE_A, PROJECTION, REVISION);

        assertEquals(WorkSessionAcceptanceState.VALIDATED, validated.state());
        assertNotNull(validated.validatedAt());
        assertEquals(WorkSessionAcceptanceState.INTEGRATION_READY, ready.state());
        assertNotNull(ready.integrationReadyAt());
        assertEquals(TREE_A, ready.sourceTreeFingerprintSha256());
        assertEquals(PROJECTION, ready.validationProjectionSha256());
    }

    @Test
    void changedTreeInvalidatesValidationAndIntegrationReadiness() {
        service.observeSourceTree(41L, TREE_A);
        service.markValidating(41L, TREE_A, PROJECTION, REVISION);
        service.markValidated(41L, TREE_A, PROJECTION, REVISION);
        service.markIntegrationReady(41L, TREE_A, PROJECTION, REVISION);

        WorkSessionAcceptanceSnapshot changed = service.observeSourceTree(41L, TREE_B);

        assertEquals(WorkSessionAcceptanceState.DRAFT, changed.state());
        assertEquals(TREE_B, changed.sourceTreeFingerprintSha256());
        assertNull(changed.validationProjectionSha256());
        assertNull(changed.validationDefinitionRevision());
        assertNull(changed.validatedAt());
        assertNull(changed.integrationReadyAt());
        assertEquals(
                "Run the required mediated validation for the current source tree",
                changed.nextAction());
    }

    @Test
    void sameTreeObservationPreservesAcceptedProjection() {
        service.observeSourceTree(41L, TREE_A);
        service.markValidating(41L, TREE_A, PROJECTION, REVISION);
        service.markValidated(41L, TREE_A, PROJECTION, REVISION);
        service.markIntegrationReady(41L, TREE_A, PROJECTION, REVISION);
        Instant readyAt = session.getIntegrationReadyAt();

        WorkSessionAcceptanceSnapshot repeated = service.observeSourceTree(41L, TREE_A);

        assertEquals(WorkSessionAcceptanceState.INTEGRATION_READY, repeated.state());
        assertEquals(readyAt, repeated.integrationReadyAt());
    }

    @Test
    void mismatchedTreeOrProjectionFailsClosedWithoutPromotion() {
        service.observeSourceTree(41L, TREE_A);
        service.markValidating(41L, TREE_A, PROJECTION, REVISION);
        service.markValidated(41L, TREE_A, PROJECTION, REVISION);

        assertThrows(
                WorkSessionOperationBlockedException.class,
                () -> service.markIntegrationReady(41L, TREE_B, PROJECTION, REVISION));
        assertThrows(
                WorkSessionOperationBlockedException.class,
                () -> service.markIntegrationReady(41L, TREE_A, "d".repeat(64), REVISION));

        assertEquals(WorkSessionAcceptanceState.VALIDATED, session.getAcceptanceState());
        assertNull(session.getIntegrationReadyAt());
    }

    @Test
    void invalidFingerprintIsRejectedBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> service.observeSourceTree(41L, "../tree"));
        verify(workSessionRepository, never()).save(any());
    }
}
