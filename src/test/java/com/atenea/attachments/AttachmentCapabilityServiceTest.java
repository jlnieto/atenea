package com.atenea.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AttachmentStorageScope;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.remoteworker.ProjectCodexIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AttachmentCapabilityServiceTest {

    @Mock
    private AttachmentWorkerClient workerClient;

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private WorkSessionAttachmentRepository attachmentRepository;

    private AttachmentProperties properties;
    private AttachmentCapabilityService service;

    @BeforeEach
    void setUp() {
        properties = new AttachmentProperties();
        properties.setEnabled(true);
        properties.setRealProjectAllowlist(Set.of(ProjectCodexIdentity.PROJECT_IDENTITY));
        RealAttachmentProjectRegistry registry = new RealAttachmentProjectRegistry(properties);
        service = new AttachmentCapabilityService(
                properties,
                new AttachmentAdmissionPolicy(properties, registry),
                workerClient,
                workSessionRepository,
                attachmentRepository);
    }

    @Test
    void readyReportsOnlyPublicStatePolicyTypesQuotaAndTurnLimits() {
        WorkSessionEntity session = exactRealSession();
        givenSession(session, 1024L);
        when(workerClient.health()).thenReturn(health());
        when(workerClient.realProjectCapability()).thenReturn(realCapability());

        AttachmentCapability capability = service.get(12L);

        assertEquals(AttachmentCapability.State.READY, capability.state());
        assertEquals(AttachmentCapability.BlockedReason.NONE, capability.blockedReason());
        assertEquals(
                AttachmentCapability.WorkerCompatibility.COMPATIBLE,
                capability.workerCompatibility());
        assertEquals(RealAttachmentProjectRegistry.ATENEA_POLICY_REVISION,
                capability.policyRevision());
        assertEquals(AttachmentProperties.TURN_IMAGE_CONTENT_TYPES,
                capability.acceptedContentTypes());
        assertEquals(1024L, capability.currentSessionBytes());
        assertEquals(AttachmentProperties.DEFAULT_MAX_SESSION_BYTES,
                capability.maxSessionBytes());
        assertEquals(
                AttachmentProperties.DEFAULT_MAX_SESSION_BYTES - 1024L,
                capability.remainingSessionBytes());
        assertEquals(AttachmentProperties.DEFAULT_MAX_FILE_BYTES,
                capability.maxFileBytes());
        assertEquals(4, capability.maxAttachmentsPerTurn());
        assertEquals(32L * 1024L * 1024L, capability.maxAttachmentBytesPerTurn());
    }

    @Test
    void globalDisableWinsAndDoesNotProbeWorker() {
        properties.setEnabled(false);
        givenSession(exactRealSession(), 2048L);

        AttachmentCapability capability = service.get(12L);

        assertBlocked(
                capability,
                AttachmentCapability.BlockedReason.GLOBAL_DISABLED,
                AttachmentCapability.WorkerCompatibility.NOT_CHECKED);
        verify(workerClient, never()).health();
        verify(workerClient, never()).realProjectCapability();
    }

    @Test
    void foreignProjectAndLegacyAteneaSessionHaveDistinctActionableReasons() {
        WorkSessionEntity foreign = exactRealSession();
        foreign.setAttachmentPolicyRevision(null);
        foreign.getProject().setName("Beautips");
        foreign.getProject().setRepoPath("/workspace/repos/internal/beautips");
        givenSession(foreign, 0L);

        AttachmentCapability foreignCapability = service.get(12L);
        assertBlocked(
                foreignCapability,
                AttachmentCapability.BlockedReason.PROJECT_DISABLED,
                AttachmentCapability.WorkerCompatibility.NOT_CHECKED);

        WorkSessionEntity legacy = exactRealSession();
        legacy.setAttachmentPolicyRevision(null);
        givenSession(legacy, 0L);

        AttachmentCapability legacyCapability = service.get(12L);
        assertBlocked(
                legacyCapability,
                AttachmentCapability.BlockedReason.SESSION_NOT_ELIGIBLE,
                AttachmentCapability.WorkerCompatibility.NOT_CHECKED);
        assertTrue(legacyCapability.nextAction().contains("WorkSession nueva"));
        verify(workerClient, never()).health();
    }

    @Test
    void partialOrForeignRealOwnershipFailsClosedBeforeWorker() {
        WorkSessionEntity session = exactRealSession();
        session.setWorkspaceIdentity("remote:ax42-01:work-session:" + UUID.randomUUID());
        givenSession(session, 0L);

        AttachmentCapability capability = service.get(12L);

        assertBlocked(
                capability,
                AttachmentCapability.BlockedReason.OWNERSHIP_INVALID,
                AttachmentCapability.WorkerCompatibility.NOT_CHECKED);
        verify(workerClient, never()).health();
        verify(workerClient, never()).realProjectCapability();
    }

    @Test
    void exhaustedQuotaBlocksWithoutWorkerAndNeverReportsNegativeRemainingBytes() {
        givenSession(exactRealSession(), AttachmentProperties.DEFAULT_MAX_SESSION_BYTES + 1L);

        AttachmentCapability capability = service.get(12L);

        assertBlocked(
                capability,
                AttachmentCapability.BlockedReason.SESSION_QUOTA_EXHAUSTED,
                AttachmentCapability.WorkerCompatibility.NOT_CHECKED);
        assertEquals(0L, capability.remainingSessionBytes());
        verify(workerClient, never()).health();
    }

    @Test
    void unavailableWorkerIsActionableAndDoesNotProbeExtension() {
        givenSession(exactRealSession(), 0L);
        when(workerClient.health()).thenThrow(new AttachmentWorkerException(
                "synthetic unavailable",
                new java.io.IOException("synthetic network failure")));

        AttachmentCapability capability = service.get(12L);

        assertBlocked(
                capability,
                AttachmentCapability.BlockedReason.WORKER_UNAVAILABLE,
                AttachmentCapability.WorkerCompatibility.UNAVAILABLE);
        verify(workerClient, never()).realProjectCapability();
    }

    @Test
    void missingOrIncompatibleRealExtensionIsReportedAsUnsupported() {
        givenSession(exactRealSession(), 0L);
        when(workerClient.health()).thenReturn(health());
        when(workerClient.realProjectCapability()).thenThrow(new AttachmentWorkerException(
                "synthetic missing",
                404,
                "not_found"));

        AttachmentCapability missing = service.get(12L);
        assertBlocked(
                missing,
                AttachmentCapability.BlockedReason.WORKER_UNSUPPORTED,
                AttachmentCapability.WorkerCompatibility.INCOMPATIBLE);

        doReturn(
                new AttachmentWorkerClient.RealProjectCapability(
                        "foreign/v1",
                        RealAttachmentProjectRegistry.ATENEA_WORKER_ID,
                        true,
                        List.of(ProjectCodexIdentity.PROJECT_IDENTITY),
                        List.of(AttachmentStorageScope.REAL_SESSION),
                        Instant.now()))
                .when(workerClient).realProjectCapability();

        AttachmentCapability incompatible = service.get(12L);
        assertBlocked(
                incompatible,
                AttachmentCapability.BlockedReason.WORKER_UNSUPPORTED,
                AttachmentCapability.WorkerCompatibility.INCOMPATIBLE);
    }

    @Test
    void workerWithoutEveryAcceptedImageTypeIsUnsupported() {
        givenSession(exactRealSession(), 0L);
        AttachmentWorkerClient.Health incomplete = new AttachmentWorkerClient.Health(
                AttachmentProperties.PROTOCOL,
                RealAttachmentProjectRegistry.ATENEA_WORKER_ID,
                true,
                AttachmentProperties.DEFAULT_MAX_FILE_BYTES,
                AttachmentProperties.DEFAULT_MAX_SESSION_BYTES,
                List.of("image/png", "image/jpeg"),
                Instant.now());
        when(workerClient.health()).thenReturn(incomplete);

        AttachmentCapability capability = service.get(12L);

        assertBlocked(
                capability,
                AttachmentCapability.BlockedReason.WORKER_UNSUPPORTED,
                AttachmentCapability.WorkerCompatibility.INCOMPATIBLE);
        verify(workerClient, never()).realProjectCapability();
    }

    private void givenSession(WorkSessionEntity session, long currentBytes) {
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(attachmentRepository.sumSizeBytesByWorkSessionId(12L)).thenReturn(currentBytes);
    }

    private void assertBlocked(
            AttachmentCapability capability,
            AttachmentCapability.BlockedReason reason,
            AttachmentCapability.WorkerCompatibility compatibility
    ) {
        assertEquals(AttachmentCapability.State.BLOCKED, capability.state());
        assertEquals(reason, capability.blockedReason());
        assertEquals(compatibility, capability.workerCompatibility());
        assertTrue(capability.message() != null && !capability.message().isBlank());
        assertTrue(capability.nextAction() != null && !capability.nextAction().isBlank());
    }

    private AttachmentWorkerClient.Health health() {
        return new AttachmentWorkerClient.Health(
                AttachmentProperties.PROTOCOL,
                RealAttachmentProjectRegistry.ATENEA_WORKER_ID,
                true,
                AttachmentProperties.DEFAULT_MAX_FILE_BYTES,
                AttachmentProperties.DEFAULT_MAX_SESSION_BYTES,
                List.of(
                        "image/png",
                        "image/jpeg",
                        "image/webp",
                        "text/plain",
                        "application/json",
                        "application/pdf",
                        "application/zip"),
                Instant.now());
    }

    private AttachmentWorkerClient.RealProjectCapability realCapability() {
        return new AttachmentWorkerClient.RealProjectCapability(
                AttachmentWorkerClient.REAL_PROJECT_PROTOCOL,
                RealAttachmentProjectRegistry.ATENEA_WORKER_ID,
                true,
                List.of(ProjectCodexIdentity.PROJECT_IDENTITY),
                List.of(AttachmentStorageScope.REAL_SESSION),
                Instant.now());
    }

    private WorkSessionEntity exactRealSession() {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setProject(project);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(RealAttachmentProjectRegistry.ATENEA_WORKER_ID);
        UUID remoteSessionId = UUID.fromString("a1c3af50-af6e-4cc2-85d6-a491c50cddcc");
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceIdentity(
                "remote:" + RealAttachmentProjectRegistry.ATENEA_WORKER_ID
                        + ":work-session:" + remoteSessionId);
        session.setAttachmentPolicyRevision(RealAttachmentProjectRegistry.ATENEA_POLICY_REVISION);
        return session;
    }
}
