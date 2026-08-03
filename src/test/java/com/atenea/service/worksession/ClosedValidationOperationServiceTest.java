package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.ValidationOperationEntity;
import com.atenea.persistence.worksession.ValidationOperationKind;
import com.atenea.persistence.worksession.ValidationOperationRepository;
import com.atenea.persistence.worksession.ValidationOperationStatus;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClosedValidationOperationServiceTest {

    private static final String COMMIT = "1".repeat(40);
    private static final String TREE = "4".repeat(64);

    @Mock private WorkSessionRepository workSessionRepository;
    @Mock private AgentRunRepository agentRunRepository;
    @Mock private ValidationOperationRepository validationOperationRepository;
    @Mock private RemoteWorkerClient remoteWorkerClient;
    @Mock private WorkSessionAcceptanceService acceptanceService;

    private final List<ValidationOperationEntity> operations = new ArrayList<>();
    private ClosedValidationOperationService service;
    private WorkSessionEntity session;

    @BeforeEach
    void setUp() {
        ProjectEntity project = new ProjectEntity();
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        session = new WorkSessionEntity();
        session.setId(41L);
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setRemoteSessionId(UUID.fromString("4bb26a65-0a0a-4ae0-b8e0-b41e03a695bf"));
        session.setWorkspaceIdentity(
                "remote:ax42-01:work-session:" + session.getRemoteSessionId());
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit(COMMIT);
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.parse("2026-07-30T12:00:00Z"));

        when(workSessionRepository.findLockedWithProjectById(41L)).thenReturn(Optional.of(session));
        when(remoteWorkerClient.fingerprintSourceTree(session)).thenReturn(new RemoteWorkerClient.SourceTreeFingerprint(
                "observed",
                session.getRemoteSessionId().toString(),
                session.getWorkspaceIdentity(),
                ProjectCodexIdentity.PROJECT_IDENTITY,
                COMMIT,
                TREE,
                0,
                0,
                0,
                false));
        when(validationOperationRepository.findByIdentitySha256(anyString())).thenAnswer(invocation ->
                operations.stream()
                        .filter(value -> value.getIdentitySha256().equals(invocation.getArgument(0)))
                        .findFirst());
        when(validationOperationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ValidationOperationEntity entity = invocation.getArgument(0);
            operations.add(entity);
            return entity;
        });
        when(validationOperationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(validationOperationRepository
                .findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByOperationAsc(41L, TREE))
                .thenAnswer(invocation -> List.copyOf(operations));
        when(remoteWorkerClient.runValidation(any(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    ValidationOperationKind kind = invocation.getArgument(1);
                    String id = invocation.getArgument(3);
                    return new RemoteWorkerClient.ValidationResult(
                            id,
                            session.getRemoteSessionId().toString(),
                            session.getWorkspaceIdentity(),
                            kind.name(),
                            kind.definitionRevision(),
                            TREE,
                            "SUCCEEDED",
                            0,
                            7,
                            "5".repeat(64),
                            "Closed validation passed",
                            false);
                });
        service = new ClosedValidationOperationService(
                workSessionRepository,
                agentRunRepository,
                validationOperationRepository,
                remoteWorkerClient,
                acceptanceService);
    }

    @Test
    void repeatedIdentityReturnsSamePersistedOperationWithoutDuplicateWorkerProcess() {
        var first = service.run(41L, ValidationOperationKind.BACKEND_TEST);
        var second = service.run(41L, ValidationOperationKind.BACKEND_TEST);

        assertEquals(first.id(), second.id());
        assertEquals(1, operations.size());
        assertEquals(ValidationOperationStatus.SUCCEEDED, first.status());
        verify(remoteWorkerClient, times(1))
                .runValidation(any(), any(), anyString(), anyString());
    }

    @Test
    void allFourFixedOperationsProduceValidatedProjectionWithoutPublishing() {
        service.run(41L, ValidationOperationKind.BACKEND_TEST);
        service.run(41L, ValidationOperationKind.WEB_BUILD);
        service.run(41L, ValidationOperationKind.ANDROID_BUILD);
        service.run(41L, ValidationOperationKind.PLAYWRIGHT_ACCEPTANCE);

        assertEquals(4, operations.size());
        verify(acceptanceService).markValidated(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(TREE),
                anyString(),
                org.mockito.ArgumentMatchers.eq("atenea-required-validation-v1"));
        verify(acceptanceService, times(0))
                .markIntegrationReady(anyLong(), anyString(), anyString(), anyString());
    }
}
