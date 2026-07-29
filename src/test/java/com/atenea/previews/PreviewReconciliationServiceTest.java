package com.atenea.previews;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.PreviewState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPreviewEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.WorkSessionPreviewMetadataService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreviewReconciliationServiceTest {

    private static final UUID PREVIEW =
            UUID.fromString("61000000-0000-4000-8000-000000000001");

    @Mock PreviewWorkerClient workerClient;
    @Mock WorkSessionRepository sessionRepository;
    @Mock WorkSessionPreviewMetadataService metadataService;

    private PreviewProperties properties;
    private PreviewReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        properties = new PreviewProperties();
        properties.setEnabled(true);
        properties.setSyntheticProjectAllowlist(Set.of("synthetic-preview"));
        WorkSessionPreviewService previewService = new WorkSessionPreviewService(
                properties, workerClient, sessionRepository, metadataService);
        reconciliationService = new PreviewReconciliationService(
                properties, workerClient, previewService, metadataService);
    }

    @Test
    void startupProjectionRestoresOnlyPersistedStartingIdentity() {
        WorkSessionPreviewEntity starting = preview(PreviewState.STARTING, 1);
        when(metadataService.reconcilable()).thenReturn(List.of(starting));
        when(workerClient.inspect(any())).thenReturn(projection("READY", 2, "ax42-01"));

        assertEquals(1, reconciliationService.reconcilePersisted());

        verify(metadataService).markReady(
                eq(PREVIEW), eq(1L), eq("http://100.81.98.93:19000/ready"),
                eq(false), any(), any(), any());
        verify(metadataService, never()).markReconciling(
                any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void foreignWorkerResponseLeavesPersistedCandidateUnchanged() {
        WorkSessionPreviewEntity starting = preview(PreviewState.STARTING, 1);
        when(metadataService.reconcilable()).thenReturn(List.of(starting));
        when(workerClient.inspect(any())).thenReturn(projection("READY", 2, "foreign"));

        assertEquals(0, reconciliationService.reconcilePersisted());

        verify(metadataService, never()).markReady(
                any(), org.mockito.ArgumentMatchers.anyLong(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());
        verify(metadataService, never()).block(
                any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
    }

    @Test
    void defaultOffDoesNotQueryOrMutatePersistedState() {
        properties.setEnabled(false);

        assertEquals(0, reconciliationService.reconcilePersisted());

        verify(metadataService, never()).reconcilable();
        verify(workerClient, never()).inspect(any());
    }

    private WorkSessionPreviewEntity preview(PreviewState state, long revision) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("synthetic-preview");
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setProject(project);
        WorkSessionPreviewEntity preview = new WorkSessionPreviewEntity();
        preview.setId(PREVIEW);
        preview.setWorkSession(session);
        preview.setProject(project);
        preview.setWorkerId("ax42-01");
        preview.setAllocationIdentity("ws-61000000000040008000000000000002");
        preview.setAllocationFingerprint("a".repeat(64));
        preview.setState(state);
        preview.setLifecycleRevision(revision);
        preview.setLeaseExpiresAt(Instant.now().plusSeconds(300));
        preview.setHardExpiresAt(Instant.now().plusSeconds(28_000));
        return preview;
    }

    private PreviewWorkerClient.Projection projection(
            String state,
            long revision,
            String workerId
    ) {
        return new PreviewWorkerClient.Projection(
                PreviewProperties.PROTOCOL,
                PREVIEW,
                "12",
                "synthetic-preview",
                workerId,
                "ws-61000000000040008000000000000002",
                "a".repeat(64),
                revision,
                state,
                "http://100.81.98.93:19000/ready",
                Instant.now().plusSeconds(300),
                Instant.now().plusSeconds(28_000),
                false,
                null,
                true);
    }
}
