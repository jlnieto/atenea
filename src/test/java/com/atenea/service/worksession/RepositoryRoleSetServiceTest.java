package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RepositoryRoleSetServiceTest {
    @Mock WorkSessionRepository sessions;
    @Mock AgentRunRepository agentRuns;
    @Mock WorkSessionRepositoryRoleRepository roles;
    @Mock RemoteWorkerClient worker;
    private final List<WorkSessionRepositoryRoleEntity> stored = new ArrayList<>();
    private WorkSessionEntity session;
    private RepositoryRoleSetService service;

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
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + session.getRemoteSessionId());
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit("1".repeat(40));
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.now());
        lenient().when(sessions.findLockedWithProjectById(41L)).thenReturn(Optional.of(session));
        lenient().when(roles.findByWorkSessionIdOrderByRoleAsc(41L))
                .thenAnswer(invocation -> stored.stream()
                        .sorted(Comparator.comparing(WorkSessionRepositoryRoleEntity::getRole))
                        .toList());
        lenient().when(roles.findByWorkSessionIdAndRole(eq(41L), any()))
                .thenAnswer(invocation -> stored.stream()
                        .filter(value -> value.getRole() == invocation.getArgument(1))
                        .findFirst());
        lenient().when(roles.save(any())).thenAnswer(invocation -> {
            WorkSessionRepositoryRoleEntity value = invocation.getArgument(0);
            if (!stored.contains(value)) stored.add(value);
            return value;
        });
        lenient().when(worker.ensureRepositoryRoles(eq(session), anyString()))
                .thenAnswer(invocation -> observed(invocation.getArgument(1)));
        service = new RepositoryRoleSetService(sessions, agentRuns, roles, worker);
    }

    @Test
    void exactThreeRoleSetIsIdempotentAndDoesNotExposePaths() {
        var first = service.ensure(41L);
        var second = service.ensure(41L);

        assertEquals(first, second);
        assertEquals(3, first.roles().size());
        assertEquals(RepositoryRoleReadiness.DRAFT, first.linkedReadiness());
        assertFalse(first.valuesExposed());
        assertEquals(EnumSet.allOf(RepositoryRoleKind.class),
                first.roles().stream().map(value -> value.role())
                        .collect(java.util.stream.Collectors.toCollection(
                                () -> EnumSet.noneOf(RepositoryRoleKind.class))));
        verify(worker, times(1)).ensureRepositoryRoles(eq(session), anyString());
    }

    @Test
    void linkedReadinessRequiresEveryRoleAndCannotSkipValidation() {
        service.ensure(41L);
        assertThrows(WorkSessionOperationBlockedException.class,
                () -> service.markIntegrationReady(41L, RepositoryRoleKind.ATENEA_CODE));
        for (RepositoryRoleKind role : RepositoryRoleKind.values()) {
            service.markValidated(41L, role, "a".repeat(64), "b".repeat(64));
        }
        assertEquals(RepositoryRoleReadiness.VALIDATED,
                service.markIntegrationReady(41L, RepositoryRoleKind.ATENEA_CODE)
                        .linkedReadiness());
        service.markIntegrationReady(41L, RepositoryRoleKind.PROGRAMME_OPENSPEC);
        assertEquals(RepositoryRoleReadiness.INTEGRATION_READY,
                service.markIntegrationReady(41L, RepositoryRoleKind.WORKER_SOURCE)
                        .linkedReadiness());
    }

    @Test
    void foreignOrIncompleteWorkerRoleSetIsRejectedWithoutPersistence() {
        when(worker.ensureRepositoryRoles(eq(session), anyString())).thenAnswer(invocation -> {
            RemoteWorkerClient.RepositoryRoleSet valid = observed(invocation.getArgument(1));
            return new RemoteWorkerClient.RepositoryRoleSet(
                    valid.sessionId(), valid.workspaceIdentity(), valid.changeIdentity(),
                    valid.roles().subList(0, 2), false);
        });
        assertThrows(WorkSessionOperationBlockedException.class, () -> service.ensure(41L));
        assertTrue(stored.isEmpty());
    }

    private RemoteWorkerClient.RepositoryRoleSet observed(String change) {
        return new RemoteWorkerClient.RepositoryRoleSet(
                session.getRemoteSessionId().toString(), session.getWorkspaceIdentity(), change,
                List.of(
                        role(RepositoryRoleKind.ATENEA_CODE, ProjectCodexIdentity.BRANCH,
                                "1".repeat(40), "atenea-code-v1"),
                        role(RepositoryRoleKind.PROGRAMME_OPENSPEC,
                                "program/remote-codex-worker-platform",
                                "3".repeat(40), "openspec-strict-v1"),
                        role(RepositoryRoleKind.WORKER_SOURCE,
                                "program/remote-codex-worker-platform",
                                "3".repeat(40), "worker-contract-v1")),
                false);
    }

    private RemoteWorkerClient.RepositoryRole role(
            RepositoryRoleKind role, String branch, String commit, String profile
    ) {
        return new RemoteWorkerClient.RepositoryRole(
                role.name(), "READ_WRITE", ProjectCodexIdentity.REPOSITORY,
                branch, commit, "6".repeat(64), "7".repeat(64),
                profile, "DRAFT");
    }
}
