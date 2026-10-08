package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.RemoteWorkerClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DevelopmentChangeValidationEvidenceServiceTest {
    private final WorkSessionRepository sessions = mock(WorkSessionRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final ValidationOperationRepository validations = mock(ValidationOperationRepository.class);
    private final RemoteWorkerClient worker = mock(RemoteWorkerClient.class);
    private final WorkSessionAcceptanceService acceptance = mock(WorkSessionAcceptanceService.class);
    private final ClosedValidationOperationService service = new ClosedValidationOperationService(
            sessions, runs, validations, worker, acceptance);
    private final String tree = "4".repeat(64);
    private WorkSessionEntity session;
    private DevelopmentChangeEntity change;

    @BeforeEach
    void setup() {
        ProjectEntity project = new ProjectEntity();
        project.setId(1L);
        change = new DevelopmentChangeEntity();
        change.setChangeKey(UUID.fromString("59315b6e-59bc-4884-9def-356e1ca86ef4"));
        change.setProject(project);
        change.setWorkspaceIdentity("change-owned-fixture");
        change.setWorkspaceBranch("change/fixture");
        change.setSelectedWorkerId("ax42-01");
        change.setSourceFingerprintSha256(tree);
        change.setSourceRevision(2);
        change.setValidationState(DevelopmentChangeProjectionState.BLOCKED);
        session = new WorkSessionEntity();
        session.setId(21L);
        session.setProject(project);
        session.setDevelopmentChange(change);
        session.setWorkspaceIdentity(change.getWorkspaceIdentity());
        session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setSelectedWorkerId(change.getSelectedWorkerId());
        when(sessions.findById(21L)).thenReturn(Optional.of(session));
    }

    @Test
    void readsExactDurableFailureWithoutRetryWorkerInspectionOrAnyWrites() {
        var failed = attempt(ValidationOperationKind.BACKEND_TEST, ValidationOperationStatus.BLOCKED, tree);
        failed.setSummary("[TEST_DATABASE_SETUP_FAILED] No se pudo preparar PostgreSQL de pruebas.");
        when(validations.findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(21L, tree))
                .thenReturn(List.of(failed));
        when(validations.findFirstByWorkSessionIdOrderByStartedAtDescIdDesc(21L)).thenReturn(Optional.of(failed));

        var first = service.getDevelopmentChangeEvidence(21L);
        var second = service.getDevelopmentChangeEvidence(21L);

        assertEquals(first, second);
        assertEquals(failed.getId(), first.lastAttempt().id());
        assertEquals(failed.getSummary(), first.lastAttempt().summary());
        assertEquals(0, first.passedOperations());
        assertEquals(4, first.requiredOperations());
        assertEquals(DevelopmentChangeProjectionState.BLOCKED, change.getValidationState());
        verifyNoInteractions(worker, runs, acceptance);
        verify(validations, never()).save(any());
        verify(validations, never()).saveAndFlush(any());
        verify(sessions, never()).findLockedWithProjectAndDevelopmentChangeById(anyLong());
    }

    @Test
    void latestAttemptReplacesOlderAttemptForEachCurrentDefinition() {
        var old = attempt(ValidationOperationKind.BACKEND_TEST, ValidationOperationStatus.FAILED, tree);
        var passed = attempt(ValidationOperationKind.BACKEND_TEST, ValidationOperationStatus.SUCCEEDED, tree);
        when(validations.findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(21L, tree))
                .thenReturn(List.of(old, passed));

        var result = service.getDevelopmentChangeEvidence(21L);
        assertEquals(1, result.operations().size());
        assertEquals(passed.getId(), result.operations().getFirst().id());
        assertEquals(1, result.passedOperations());
        verifyNoInteractions(worker, acceptance);
    }

    @Test
    void retainsOldSourceFailureWithoutCountingItAsCurrentValidation() {
        var old = attempt(ValidationOperationKind.BACKEND_TEST, ValidationOperationStatus.FAILED, "5".repeat(64));
        when(validations.findFirstByWorkSessionIdOrderByStartedAtDescIdDesc(21L)).thenReturn(Optional.of(old));
        var result = service.getDevelopmentChangeEvidence(21L);
        assertTrue(result.operations().isEmpty());
        assertEquals(0, result.passedOperations());
        assertNotEquals(result.sourceFingerprintSha256(), result.lastAttempt().sourceTreeFingerprintSha256());
    }

    @Test
    void obsoleteDefinitionNeverCountsAsPassed() {
        var obsolete = attempt(ValidationOperationKind.BACKEND_TEST, ValidationOperationStatus.SUCCEEDED, tree);
        obsolete.setDefinitionRevision("previous-definition");
        when(validations.findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(21L, tree))
                .thenReturn(List.of(obsolete));
        assertEquals(0, service.getDevelopmentChangeEvidence(21L).passedOperations());
    }

    @Test
    void rejectsMismatchedWorkspaceWithoutLookingUpOrStartingOperations() {
        session.setWorkspaceIdentity("foreign-workspace");
        assertThrows(WorkSessionOperationBlockedException.class, () -> service.getDevelopmentChangeEvidence(21L));
        verifyNoInteractions(validations, worker, acceptance);
    }

    @Test
    void rejectsOtherProjectEvenWhenOtherIdentityFieldsMatch() {
        ProjectEntity foreign = new ProjectEntity();
        foreign.setId(2L);
        session.setProject(foreign);
        assertThrows(WorkSessionOperationBlockedException.class, () -> service.getDevelopmentChangeEvidence(21L));
        verifyNoInteractions(validations, worker, acceptance);
    }

    @Test
    void rejectsMissingSessionWithoutWorkerCall() {
        assertThrows(WorkSessionNotFoundException.class, () -> service.getDevelopmentChangeEvidence(22L));
        verifyNoInteractions(validations, worker, acceptance);
    }

    private ValidationOperationEntity attempt(ValidationOperationKind kind, ValidationOperationStatus status, String source) {
        var result = new ValidationOperationEntity();
        result.setId(UUID.randomUUID());
        result.setWorkSession(session);
        result.setOperation(kind);
        result.setDefinitionRevision(kind.definitionRevision());
        result.setStatus(status);
        result.setSourceTreeFingerprintSha256(source);
        result.setSummary("Persisted validation result");
        result.setStartedAt(Instant.parse("2026-10-05T10:00:00Z"));
        return result;
    }
}
