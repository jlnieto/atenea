package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.api.worksession.SessionTurnAttachmentResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.SessionTurnAttachmentRepository;
import com.atenea.persistence.worksession.SessionTurnAttachmentEntity;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.git.GitRepositoryService;
import com.atenea.remoteworker.CanonicalSourceAdmissionService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class SessionTurnServiceTest {

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private SessionTurnRepository sessionTurnRepository;

    @Mock
    private SessionTurnAttachmentRepository sessionTurnAttachmentRepository;

    @Mock
    private WorkSessionAttachmentRepository workSessionAttachmentRepository;

    @Mock
    private GitRepositoryService gitRepositoryService;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private AgentRunService agentRunService;

    @Mock
    private AgentRunReconciliationService agentRunReconciliationService;

    @Mock
    private SessionCodexOrchestrator sessionCodexOrchestrator;

    @Mock
    private SessionTurnCompletionService sessionTurnCompletionService;

    @Mock
    private CanonicalSourceAdmissionService canonicalSourceAdmissionService;

    @Mock
    private TurnAttachmentSelectionValidator turnAttachmentSelectionValidator;

    @Mock
    private TurnAttachmentFingerprintService turnAttachmentFingerprintService;

    private SessionTurnService sessionTurnService;

    @BeforeEach
    void setUp() {
        sessionTurnService = new SessionTurnService(
                workSessionRepository,
                sessionTurnRepository,
                sessionTurnAttachmentRepository,
                workSessionAttachmentRepository,
                new WorkspaceRepositoryPathValidator("/workspace/repos"),
                gitRepositoryService,
                agentRunRepository,
                agentRunService,
                new AgentRunProgressService(),
                agentRunReconciliationService,
                sessionCodexOrchestrator,
                sessionTurnCompletionService,
                canonicalSourceAdmissionService,
                turnAttachmentSelectionValidator,
                turnAttachmentFingerprintService
        );
    }

    @Test
    void getTurnsReturnsVisibleTurnsInChronologicalOrder() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        when(sessionTurnRepository.findBySessionIdAndInternalFalseOrderByCreatedAtAsc(12L)).thenReturn(List.of(
                buildTurn(101L, SessionTurnActor.OPERATOR, "First question", false, "2026-03-25T10:05:00Z"),
                buildTurn(102L, SessionTurnActor.CODEX, "First answer", false, "2026-03-25T10:06:00Z")));

        List<SessionTurnResponse> turns = sessionTurnService.getTurns(12L);

        assertEquals(2, turns.size());
        assertEquals(101L, turns.get(0).id());
        assertEquals("First question", turns.get(0).messageText());
        assertEquals(102L, turns.get(1).id());
        assertEquals("First answer", turns.get(1).messageText());
    }

    @Test
    void getTurnsProjectsOnlyOrderedPublicAttachmentMetadataOnItsExactTurn() {
        SessionTurnEntity operatorTurn = buildTurn(
                101L,
                SessionTurnActor.OPERATOR,
                "Inspect both images",
                false,
                "2026-03-25T10:05:00Z");
        SessionTurnEntity codexTurn = buildTurn(
                102L,
                SessionTurnActor.CODEX,
                "Done",
                false,
                "2026-03-25T10:06:00Z");
        UUID firstId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
        UUID secondId = UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d");
        SessionTurnAttachmentEntity firstBinding = binding(101L, firstId, (short) 0);
        SessionTurnAttachmentEntity secondBinding = binding(101L, secondId, (short) 1);
        WorkSessionAttachmentEntity first = image(
                firstId, "screen-one.png", "image/png", 1024L, "a".repeat(64));
        WorkSessionAttachmentEntity second = image(
                secondId, "screen-two.webp", "image/webp", 2048L, "b".repeat(64));
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        when(sessionTurnRepository.findBySessionIdAndInternalFalseOrderByCreatedAtAsc(12L))
                .thenReturn(List.of(operatorTurn, codexTurn));
        when(sessionTurnAttachmentRepository
                .findByWorkSessionIdAndSessionTurnIdInOrderBySessionTurnIdAscPositionAsc(
                        12L,
                        List.of(101L, 102L)))
                .thenReturn(List.of(firstBinding, secondBinding));
        when(workSessionAttachmentRepository.findAllById(Set.of(firstId, secondId)))
                .thenReturn(List.of(second, first));

        List<SessionTurnResponse> turns = sessionTurnService.getTurns(12L);

        assertEquals(2, turns.get(0).attachments().size());
        assertEquals(List.of(firstId, secondId), turns.get(0).attachments().stream()
                .map(SessionTurnAttachmentResponse::id)
                .toList());
        assertEquals(List.of((short) 0, (short) 1), turns.get(0).attachments().stream()
                .map(SessionTurnAttachmentResponse::position)
                .toList());
        assertEquals(
                "/api/sessions/12/attachments/" + firstId + "/content",
                turns.get(0).attachments().get(0).downloadPath());
        assertEquals(List.of(), turns.get(1).attachments());
        assertEquals(
                Set.of("id", "position", "originalFilename", "contentType", "sizeBytes", "sha256", "downloadPath"),
                java.util.Arrays.stream(SessionTurnAttachmentResponse.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void getTurnsReturnsLatestWindowWhenLimitIsProvided() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        when(sessionTurnRepository.findBySessionIdAndInternalFalseOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(12L),
                any(Pageable.class))).thenReturn(List.of(
                        buildTurn(104L, SessionTurnActor.CODEX, "Turn 4", false, "2026-03-25T10:04:00Z"),
                        buildTurn(103L, SessionTurnActor.OPERATOR, "Turn 3", false, "2026-03-25T10:03:00Z")));

        List<SessionTurnResponse> turns = sessionTurnService.getTurns(12L, null, 2);

        assertEquals(List.of(103L, 104L), turns.stream().map(SessionTurnResponse::id).toList());
    }

    @Test
    void getTurnsReturnsOlderWindowBeforeTurnId() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        when(sessionTurnRepository.findBySessionIdAndInternalFalseAndIdLessThanOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq(105L),
                any(Pageable.class))).thenReturn(List.of(
                        buildTurn(104L, SessionTurnActor.CODEX, "Turn 4", false, "2026-03-25T10:04:00Z"),
                        buildTurn(103L, SessionTurnActor.OPERATOR, "Turn 3", false, "2026-03-25T10:03:00Z")));

        List<SessionTurnResponse> turns = sessionTurnService.getTurns(12L, 105L, 2);

        assertEquals(List.of(103L, 104L), turns.stream().map(SessionTurnResponse::id).toList());
    }

    @Test
    void getTurnsExcludesInternalTurnsFromPublicHistory() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        when(sessionTurnRepository.findBySessionIdAndInternalFalseOrderByCreatedAtAsc(12L)).thenReturn(List.of(
                buildTurn(102L, SessionTurnActor.OPERATOR, "Visible operator turn", false, "2026-03-25T10:06:00Z"),
                buildTurn(103L, SessionTurnActor.CODEX, "Visible codex turn", false, "2026-03-25T10:07:00Z")));

        List<SessionTurnResponse> turns = sessionTurnService.getTurns(12L);

        assertEquals(2, turns.size());
        assertEquals(List.of(102L, 103L), turns.stream().map(SessionTurnResponse::id).toList());
    }

    @Test
    void getTurnsReturnsEmptyWindowWhenNoVisibleTurnsExistBeforeCursor() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        when(sessionTurnRepository.findBySessionIdAndInternalFalseAndIdLessThanOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq(101L),
                any(Pageable.class))).thenReturn(List.of());

        List<SessionTurnResponse> turns = sessionTurnService.getTurns(12L, 101L, 20);

        assertEquals(List.of(), turns);
    }

    @Test
    void getTurnsThrowsWhenSessionDoesNotExist() {
        when(workSessionRepository.existsById(12L)).thenReturn(false);

        assertThrows(WorkSessionNotFoundException.class, () -> sessionTurnService.getTurns(12L));
    }

    @Test
    void getTurnsThrowsWhenLimitIsInvalid() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> sessionTurnService.getTurns(12L, null, 0));

        assertEquals("Turn limit must be greater than zero", exception.getMessage());
    }

    private static SessionTurnEntity buildTurn(
            Long turnId,
            SessionTurnActor actor,
            String messageText,
            boolean internal,
            String createdAt
    ) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("Atenea");
        project.setRepoPath("/workspace/repos/internal/atenea");
        project.setCreatedAt(Instant.parse("2026-03-25T10:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-25T10:00:00Z"));

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Inspect project status");
        session.setBaseBranch("main");
        session.setOpenedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setCreatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:05:00Z"));

        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setId(turnId);
        turn.setSession(session);
        turn.setActor(actor);
        turn.setMessageText(messageText);
        turn.setInternal(internal);
        turn.setCreatedAt(Instant.parse(createdAt));
        return turn;
    }

    private static SessionTurnAttachmentEntity binding(
            Long turnId,
            UUID attachmentId,
            short position
    ) {
        SessionTurnAttachmentEntity binding = mock(SessionTurnAttachmentEntity.class);
        when(binding.getSessionTurnId()).thenReturn(turnId);
        when(binding.getAttachmentId()).thenReturn(attachmentId);
        when(binding.getPosition()).thenReturn(position);
        return binding;
    }

    private static WorkSessionAttachmentEntity image(
            UUID id,
            String filename,
            String contentType,
            long sizeBytes,
            String sha256
    ) {
        WorkSessionAttachmentEntity attachment = new WorkSessionAttachmentEntity();
        attachment.setId(id);
        attachment.setKind(AttachmentKind.IMAGE);
        attachment.setOriginalFilename(filename);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(sizeBytes);
        attachment.setSha256(sha256);
        return attachment;
    }
}
