package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
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
        assertNull(session.getRemoteSessionId());
        assertNull(session.getRemoteWorkloadKind());
        assertEquals(RemoteCloseState.NOT_REQUIRED, session.getRemoteCloseState());
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
        assertNotNull(session.getRemoteSessionId());
        assertEquals("synthetic-routing-v1", session.getRemoteWorkloadKind());
        assertEquals(
                "remote:ax42-01:work-session:" + session.getRemoteSessionId(),
                session.getWorkspaceIdentity());
        assertEquals(RemoteCloseState.NOT_STARTED, session.getRemoteCloseState());
    }

    @Test
    void exactAteneaProjectUsesProjectCodexCapabilityOnlyWhenSeparatelyEnabled() {
        properties.setEnabled(true);
        properties.setProjectCodexEnabled(true);
        when(workerNodeRepository.findById("ax42-01")).thenReturn(Optional.empty());
        when(workerNodeRepository.save(any(WorkerNodeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(client.health()).thenReturn(new RemoteWorkerClient.Health(
                RemoteWorkerProperties.PROTOCOL,
                "ax42-01",
                true,
                List.of("synthetic-routing-v1", ProjectCodexIdentity.WORKLOAD_KIND),
                4,
                2,
                0,
                0,
                0,
                Instant.parse("2026-07-29T06:00:00Z")));
        WorkSessionEntity session = session(ProjectCodexIdentity.PROJECT_NAME);

        selector.pinNewSession(session);

        assertEquals(ExecutionTarget.REMOTE, session.getExecutionTarget());
        assertEquals(ProjectCodexIdentity.WORKLOAD_KIND, session.getRemoteWorkloadKind());
        assertNotNull(session.getRemoteSessionId());
        assertTrue(session.getWorkspaceIdentity().endsWith(session.getRemoteSessionId().toString()));
        assertEquals(RemoteCloseState.NOT_STARTED, session.getRemoteCloseState());
    }

    @Test
    void exactAteneaProjectFailsClosedWhenWorkerDoesNotAdvertiseProjectCapability() {
        properties.setEnabled(true);
        properties.setProjectCodexEnabled(true);
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
                Instant.parse("2026-07-29T06:00:00Z")));
        WorkSessionEntity session = session(ProjectCodexIdentity.PROJECT_NAME);

        selector.pinNewSession(session);

        assertEquals(ExecutionTarget.LOCAL, session.getExecutionTarget());
        assertNull(session.getRemoteSessionId());
        assertNull(session.getRemoteWorkloadKind());
        assertEquals(RemoteCloseState.NOT_REQUIRED, session.getRemoteCloseState());
    }

    @Test
    void exactBeautipsProjectUsesProjectCodexOnlyWhenSeparatelyEnabled() {
        properties.setEnabled(true);
        properties.setBeautipsProjectCodexEnabled(true);
        when(workerNodeRepository.findById("ax42-01")).thenReturn(Optional.empty());
        when(workerNodeRepository.save(any(WorkerNodeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(client.health()).thenReturn(new RemoteWorkerClient.Health(
                RemoteWorkerProperties.PROTOCOL,
                "ax42-01",
                true,
                List.of(ProjectCodexIdentity.WORKLOAD_KIND),
                4,
                2,
                0,
                0,
                0,
                Instant.parse("2026-07-29T18:00:00Z")));
        WorkSessionEntity session = beautipsSession();

        selector.pinNewSession(session);

        assertEquals(ExecutionTarget.REMOTE, session.getExecutionTarget());
        assertEquals("ax42-01", session.getSelectedWorkerId());
        assertEquals(ProjectCodexIdentity.WORKLOAD_KIND, session.getRemoteWorkloadKind());
        assertNotNull(session.getRemoteSessionId());
        assertEquals(
                "remote:ax42-01:work-session:" + session.getRemoteSessionId(),
                session.getWorkspaceIdentity());
    }

    @Test
    void beautipsRemainsLocalWhileItsSeparateGateIsDisabled() {
        properties.setEnabled(true);
        properties.setProjectCodexEnabled(true);
        WorkSessionEntity session = beautipsSession();

        selector.pinNewSession(session);

        assertEquals(ExecutionTarget.LOCAL, session.getExecutionTarget());
        assertNull(session.getRemoteSessionId());
        assertNull(session.getRemoteWorkloadKind());
        verify(client, never()).health();
    }

    @Test
    void beautipsSelectionRejectsEveryPartialOrForeignIdentity() {
        properties.setEnabled(true);
        properties.setBeautipsProjectCodexEnabled(true);

        WorkSessionEntity foreignName = beautipsSession();
        foreignName.getProject().setName("Beautips Preview");
        WorkSessionEntity foreignPath = beautipsSession();
        foreignPath.getProject().setRepoPath("/workspace/repos/internal/foreign");
        WorkSessionEntity foreignDefaultBranch = beautipsSession();
        foreignDefaultBranch.getProject().setDefaultBaseBranch("foreign");
        WorkSessionEntity foreignSessionBranch = beautipsSession();
        foreignSessionBranch.setBaseBranch("foreign");

        for (WorkSessionEntity session : List.of(
                foreignName, foreignPath, foreignDefaultBranch, foreignSessionBranch)) {
            selector.pinNewSession(session);
            assertEquals(ExecutionTarget.LOCAL, session.getExecutionTarget());
            assertNull(session.getRemoteSessionId());
            assertNull(session.getRemoteWorkloadKind());
        }
        verify(client, never()).health();
    }

    @Test
    void exactBeautipsFailsClosedWithoutAdvertisedProjectCapability() {
        properties.setEnabled(true);
        properties.setBeautipsProjectCodexEnabled(true);
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
                Instant.parse("2026-07-29T18:00:00Z")));
        WorkSessionEntity session = beautipsSession();

        selector.pinNewSession(session);

        assertEquals(ExecutionTarget.LOCAL, session.getExecutionTarget());
        assertNull(session.getRemoteSessionId());
        assertNull(session.getRemoteWorkloadKind());
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

    @Test
    void refreshKnownWorkerReplacesStaleProjectionWithCurrentHealth() {
        WorkerNodeEntity stale = staleWorker();
        when(workerNodeRepository.findById("ax42-01")).thenReturn(Optional.of(stale));
        when(workerNodeRepository.save(any(WorkerNodeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(client.health()).thenReturn(health(
                true,
                RemoteWorkerProperties.PROTOCOL,
                List.of(
                        ProjectCodexIdentity.WORKLOAD_KIND,
                        "project-codex-v2",
                        ProjectCodexIdentity.CHANGE_WORKLOAD_KIND)));

        WorkerNodeEntity refreshed = selector.refreshKnownWorker(
                "ax42-01", ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);

        assertTrue(refreshed.isEnabled());
        assertTrue(refreshed.isHealthy());
        assertEquals(RemoteWorkerProperties.PROTOCOL, refreshed.getProtocolVersion());
        assertEquals("http://100.64.0.2:8787", refreshed.getEndpoint());
        assertEquals(6, refreshed.getNormalCapacity());
        assertEquals(3, refreshed.getHeavyCapacity());
        assertEquals(2, refreshed.getNormalInUse());
        assertEquals(1, refreshed.getHeavyInUse());
        assertEquals(
                ProjectCodexIdentity.WORKLOAD_KIND + ",project-codex-v2,"
                        + ProjectCodexIdentity.CHANGE_WORKLOAD_KIND,
                refreshed.getCapabilities());
        assertNotNull(refreshed.getLastHeartbeatAt());
        assertTrue(refreshed.getUpdatedAt().isAfter(
                Instant.parse("2026-08-10T00:00:00Z")));
        assertNull(refreshed.getUnavailableReason());
    }

    @Test
    void refreshKnownWorkerStoresRuntimeCapabilitiesWithoutV4AndDisablesAdmission() {
        WorkerNodeEntity stale = staleWorker();
        stale.setCapabilities(ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);
        when(workerNodeRepository.findById("ax42-01")).thenReturn(Optional.of(stale));
        when(workerNodeRepository.save(any(WorkerNodeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(client.health()).thenReturn(health(
                true,
                RemoteWorkerProperties.PROTOCOL,
                List.of(ProjectCodexIdentity.WORKLOAD_KIND, "project-codex-v2")));

        WorkerNodeEntity refreshed = selector.refreshKnownWorker(
                "ax42-01", ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);

        assertFalse(refreshed.isEnabled());
        assertTrue(refreshed.isHealthy());
        assertEquals("project-codex-v1,project-codex-v2", refreshed.getCapabilities());
        assertNotNull(refreshed.getUnavailableReason());
    }

    @Test
    void refreshKnownWorkerFailsClosedWhenRuntimeIsUnreachable() {
        WorkerNodeEntity stale = staleWorker();
        stale.setCapabilities(ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);
        when(workerNodeRepository.findById("ax42-01")).thenReturn(Optional.of(stale));
        when(workerNodeRepository.save(any(WorkerNodeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(client.health()).thenThrow(new RemoteWorkerException("connection refused", 503));

        WorkerNodeEntity refreshed = selector.refreshKnownWorker(
                "ax42-01", ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);

        assertFalse(refreshed.isEnabled());
        assertFalse(refreshed.isHealthy());
        assertEquals("connection refused", refreshed.getUnavailableReason());
    }

    @Test
    void refreshKnownWorkerFailsClosedForUnhealthyOrProtocolMismatch() {
        for (RemoteWorkerClient.Health health : List.of(
                health(false, RemoteWorkerProperties.PROTOCOL,
                        List.of(ProjectCodexIdentity.CHANGE_WORKLOAD_KIND)),
                health(true, "agent-run-worker/v0",
                        List.of(ProjectCodexIdentity.CHANGE_WORKLOAD_KIND)))) {
            WorkerNodeEntity stale = staleWorker();
            when(workerNodeRepository.findById("ax42-01")).thenReturn(Optional.of(stale));
            when(workerNodeRepository.save(any(WorkerNodeEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(client.health()).thenReturn(health);

            WorkerNodeEntity refreshed = selector.refreshKnownWorker(
                    "ax42-01", ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);

            assertFalse(refreshed.isEnabled());
            assertEquals(health.healthy(), refreshed.isHealthy());
            assertEquals(health.protocolVersion(), refreshed.getProtocolVersion());
        }
    }

    @Test
    void refreshKnownWorkerRejectsInvalidHealthWithoutTrustingStaleV4() {
        WorkerNodeEntity stale = staleWorker();
        stale.setCapabilities(ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);
        when(workerNodeRepository.findById("ax42-01")).thenReturn(Optional.of(stale));
        when(workerNodeRepository.save(any(WorkerNodeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(client.health()).thenReturn(new RemoteWorkerClient.Health(
                RemoteWorkerProperties.PROTOCOL,
                "ax42-01",
                true,
                null,
                6,
                3,
                2,
                1,
                0,
                Instant.parse("2026-08-27T12:00:00Z")));

        WorkerNodeEntity refreshed = selector.refreshKnownWorker(
                "ax42-01", ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);

        assertFalse(refreshed.isEnabled());
        assertFalse(refreshed.isHealthy());
    }

    @Test
    void refreshKnownWorkerRejectsUnexpectedIdentityWithoutUpdatingForeignProjection() {
        WorkerNodeEntity stale = staleWorker();
        stale.setCapabilities(ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);
        when(workerNodeRepository.findById("ax42-01")).thenReturn(Optional.of(stale));
        when(workerNodeRepository.save(any(WorkerNodeEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        RemoteWorkerClient.Health unexpected = health(
                true,
                RemoteWorkerProperties.PROTOCOL,
                List.of(ProjectCodexIdentity.CHANGE_WORKLOAD_KIND));
        when(client.health()).thenReturn(new RemoteWorkerClient.Health(
                unexpected.protocolVersion(),
                "unexpected-worker",
                unexpected.healthy(),
                unexpected.capabilities(),
                unexpected.normalCapacity(),
                unexpected.heavyCapacity(),
                unexpected.normalInUse(),
                unexpected.heavyInUse(),
                unexpected.queued(),
                unexpected.serverTime()));

        WorkerNodeEntity refreshed = selector.refreshKnownWorker(
                "ax42-01", ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);

        assertFalse(refreshed.isEnabled());
        assertFalse(refreshed.isHealthy());
        verify(workerNodeRepository, never()).findById("unexpected-worker");
    }

    private WorkerNodeEntity staleWorker() {
        WorkerNodeEntity worker = new WorkerNodeEntity();
        worker.setId("ax42-01");
        worker.setProtocolVersion(RemoteWorkerProperties.PROTOCOL);
        worker.setEndpoint("http://old-endpoint:8787");
        worker.setEnabled(true);
        worker.setHealthy(true);
        worker.setNormalCapacity(4);
        worker.setHeavyCapacity(2);
        worker.setNormalInUse(0);
        worker.setHeavyInUse(0);
        worker.setCapabilities("project-codex-v1,project-codex-v2");
        worker.setLastHeartbeatAt(Instant.parse("2026-08-10T00:00:00Z"));
        worker.setCreatedAt(Instant.parse("2026-07-28T00:00:00Z"));
        worker.setUpdatedAt(Instant.parse("2026-08-10T00:00:00Z"));
        return worker;
    }

    private RemoteWorkerClient.Health health(
            boolean healthy,
            String protocol,
            List<String> capabilities
    ) {
        return new RemoteWorkerClient.Health(
                protocol,
                "ax42-01",
                healthy,
                capabilities,
                6,
                3,
                2,
                1,
                0,
                Instant.parse("2026-08-27T12:00:00Z"));
    }

    private WorkSessionEntity session(String projectName) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(projectName);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setProject(project);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        return session;
    }

    private WorkSessionEntity beautipsSession() {
        ProjectEntity project = new ProjectEntity();
        project.setId(8L);
        project.setName(BeautipsProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(BeautipsProjectCodexIdentity.REPO_PATH);
        project.setDefaultBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(42L);
        session.setProject(project);
        session.setBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        return session;
    }
}
