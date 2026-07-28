package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkerNodeEntity;
import com.atenea.persistence.worksession.WorkerNodeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RemoteRoutingSelectorTest {

    @Mock
    private RemoteWorkerClient client;

    @Mock
    private WorkerNodeRepository workerNodeRepository;

    private RemoteWorkerProperties properties;
    private RemoteRoutingSelector selector;

    @BeforeEach
    void setUp() {
        properties = new RemoteWorkerProperties();
        properties.setWorkerId("ax42-01");
        properties.setEndpoint("http://100.64.0.2:8787");
        selector = new RemoteRoutingSelector(properties, client, workerNodeRepository);
    }

    @Test
    void defaultDisabledPinsNewSessionLocallyWithoutContactingWorker() {
        WorkSessionEntity session = session("Synthetic routing");

        selector.pinNewSession(session);

        assertEquals(ExecutionTarget.LOCAL, session.getExecutionTarget());
        assertEquals("local:work-session:41", session.getWorkspaceIdentity());
        assertNull(session.getSelectedWorkerId());
        verify(client, never()).health();
    }

    @Test
    void enabledExactAllowlistPinsHealthyCompatibleWorker() {
        properties.setEnabled(true);
        properties.setSyntheticProjectAllowlist(Set.of("Synthetic routing"));
        when(workerNodeRepository.findById("ax42-01")).thenReturn(Optional.empty());
        when(workerNodeRepository.save(any(WorkerNodeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(client.health()).thenReturn(new RemoteWorkerClient.Health(
                RemoteWorkerProperties.PROTOCOL,
                "ax42-01",
                true,
                List.of("synthetic-routing-v1"),
                4,
                2,
                0,
                0,
                0,
                Instant.parse("2026-07-28T22:00:00Z")));
        WorkSessionEntity session = session("Synthetic routing");

        selector.pinNewSession(session);

        assertEquals(ExecutionTarget.REMOTE, session.getExecutionTarget());
        assertEquals("ax42-01", session.getSelectedWorkerId());
        assertEquals("remote:ax42-01:work-session:41", session.getWorkspaceIdentity());
    }

    @Test
    void enabledButDifferentProjectRemainsLocal() {
        properties.setEnabled(true);
        properties.setSyntheticProjectAllowlist(Set.of("Synthetic routing"));
        WorkSessionEntity session = session("Beautips");

        selector.pinNewSession(session);

        assertEquals(ExecutionTarget.LOCAL, session.getExecutionTarget());
        verify(client, never()).health();
    }

    @Test
    void unavailableWorkerFailsSafelyToLocalAndRecordsReason() {
        properties.setEnabled(true);
        properties.setSyntheticProjectAllowlist(Set.of("Synthetic routing"));
        when(client.health()).thenThrow(new RemoteWorkerException("connection refused", 503));
        when(workerNodeRepository.findById("ax42-01")).thenReturn(Optional.empty());
        when(workerNodeRepository.save(any(WorkerNodeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        WorkSessionEntity session = session("Synthetic routing");

        selector.pinNewSession(session);

        assertEquals(ExecutionTarget.LOCAL, session.getExecutionTarget());
        assertNull(session.getSelectedWorkerId());
        verify(workerNodeRepository).save(any(WorkerNodeEntity.class));
    }

    @Test
    void incompatibleWorkerFailsSafelyWithoutRemoteAffinity() {
        properties.setEnabled(true);
        properties.setSyntheticProjectAllowlist(Set.of("Synthetic routing"));
        when(workerNodeRepository.findById("unexpected-worker")).thenReturn(Optional.empty());
        when(workerNodeRepository.save(any(WorkerNodeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(client.health()).thenReturn(new RemoteWorkerClient.Health(
                "agent-run-worker/v0",
                "unexpected-worker",
                true,
                List.of(),
                4,
                2,
                0,
                0,
                0,
                Instant.parse("2026-07-28T22:00:00Z")));
        WorkSessionEntity session = session("Synthetic routing");

        selector.pinNewSession(session);

        assertEquals(ExecutionTarget.LOCAL, session.getExecutionTarget());
        assertNull(session.getSelectedWorkerId());
    }

    private WorkSessionEntity session(String projectName) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(projectName);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setProject(project);
        return session;
    }
}
