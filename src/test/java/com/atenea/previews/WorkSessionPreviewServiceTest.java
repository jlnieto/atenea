package com.atenea.previews;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.PreviewState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPreviewEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.PreviewOwnershipException;
import com.atenea.service.worksession.WorkSessionPreviewMetadataService;
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
class WorkSessionPreviewServiceTest {

    private static final UUID PREVIEW =
            UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID RUNTIME =
            UUID.fromString("61000000-0000-4000-8000-000000000002");

    @Mock PreviewWorkerClient workerClient;
    @Mock WorkSessionRepository sessionRepository;
    @Mock WorkSessionPreviewMetadataService metadataService;

    private PreviewProperties properties;
    private WorkSessionPreviewService service;

    @BeforeEach
    void setUp() {
        properties = new PreviewProperties();
        properties.setEnabled(true);
        properties.setSyntheticProjectAllowlist(Set.of("synthetic-preview"));
        service = new WorkSessionPreviewService(
                properties, workerClient, sessionRepository, metadataService);
    }

    @Test
    void activationDerivesWorkerProjectAndAllocationIdentityOnServer() {
        WorkSessionEntity session = session();
        WorkSessionPreviewEntity starting = preview(session, PreviewState.STARTING, 1);
        WorkSessionPreviewEntity ready = preview(session, PreviewState.READY, 2);
        when(sessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        when(metadataService.create(eq(12L), any())).thenReturn(starting);
        when(workerClient.health()).thenReturn(health());
        when(workerClient.activate(any(), eq(RUNTIME))).thenReturn(projection(2, "READY"));
        when(metadataService.markReady(
                eq(PREVIEW), eq(1L), any(), eq(true), any(), any(), any()))
                .thenReturn(ready);

        WorkSessionPreviewEntity result = service.activate(
                12L, new PreviewActivationCommand(PREVIEW, null, RUNTIME, "a".repeat(64)));

        assertEquals(PreviewState.READY, result.getState());
        verify(workerClient).activate(
                org.mockito.ArgumentMatchers.argThat(value ->
                        value.workSessionId().equals("12")
                                && value.projectId().equals("synthetic-preview")
                                && value.workerId().equals("ax42-01")
                                && value.allocationIdentity().equals(
                                "ws-61000000000040008000000000000002")),
                eq(RUNTIME));
    }

    @Test
    void foreignSessionFailsBeforeWorkerAccess() {
        WorkSessionEntity session = session();
        session.setSelectedWorkerId("foreign-worker");
        when(sessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));

        assertThrows(PreviewOwnershipException.class, () -> service.activate(
                12L, new PreviewActivationCommand(PREVIEW, null, RUNTIME, "a".repeat(64))));

        verify(workerClient, never()).health();
        verify(metadataService, never()).create(any(), any());
    }

    @Test
    void disabledCreationStillAllowsRetainedStatusRead() {
        properties.setEnabled(false);
        WorkSessionPreviewEntity retained = preview(session(), PreviewState.STOPPED, 3);
        when(metadataService.latest(12L)).thenReturn(retained);

        assertThrows(PreviewFeatureDisabledException.class, () -> service.activate(
                12L, new PreviewActivationCommand(PREVIEW, null, RUNTIME, "a".repeat(64))));
        assertEquals(PreviewState.STOPPED, service.status(12L).getState());
    }

    @Test
    void localhostCommandUsesOnlyPrivateIngressAndCallerLoopback() {
        WorkSessionPreviewEntity ready = preview(session(), PreviewState.READY, 2);
        ready.setLocalhostCompatible(true);
        when(metadataService.get(12L, PREVIEW)).thenReturn(ready);
        when(workerClient.inspect(any())).thenReturn(projection(2, "READY"));

        PreviewTunnel tunnel = service.localhost(12L, PREVIEW, 18123);

        assertEquals(
                "ssh -N -L 127.0.0.1:18123:100.81.98.93:19000 codex-worker",
                tunnel.command());
        assertEquals("http://127.0.0.1:18123/ready", tunnel.localUrl());
    }

    private PreviewWorkerClient.Health health() {
        return new PreviewWorkerClient.Health(
                PreviewProperties.PROTOCOL,
                "ax42-01",
                true,
                "100.81.98.93",
                List.of(19000, 19031),
                0,
                false,
                false,
                Instant.now());
    }

    private PreviewWorkerClient.Projection projection(long revision, String state) {
        return new PreviewWorkerClient.Projection(
                PreviewProperties.PROTOCOL,
                PREVIEW,
                "12",
                "synthetic-preview",
                "ax42-01",
                "ws-61000000000040008000000000000002",
                "a".repeat(64),
                revision,
                state,
                "READY".equals(state) ? "http://100.81.98.93:19000/ready" : null,
                Instant.now().plusSeconds(300),
                Instant.now().plusSeconds(28_000),
                true,
                new PreviewWorkerClient.Tunnel(
                        "codex-worker", "100.81.98.93", 19000, "/ready", false, false),
                true);
    }

    private WorkSessionEntity session() {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("synthetic-preview");
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setProject(project);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        return session;
    }

    private WorkSessionPreviewEntity preview(
            WorkSessionEntity session,
            PreviewState state,
            long revision
    ) {
        WorkSessionPreviewEntity preview = new WorkSessionPreviewEntity();
        preview.setId(PREVIEW);
        preview.setWorkSession(session);
        preview.setProject(session.getProject());
        preview.setWorkerId("ax42-01");
        preview.setAllocationIdentity("ws-61000000000040008000000000000002");
        preview.setAllocationFingerprint("a".repeat(64));
        preview.setState(state);
        preview.setLifecycleRevision(revision);
        preview.setPrivateUrl(state == PreviewState.READY
                ? "http://100.81.98.93:19000/ready" : null);
        preview.setLeaseExpiresAt(Instant.now().plusSeconds(300));
        preview.setHardExpiresAt(Instant.now().plusSeconds(28_000));
        preview.setAuditRetainUntil(Instant.now().plusSeconds(2_000_000));
        preview.setNextAction("Abre el preview privado.");
        return preview;
    }
}
