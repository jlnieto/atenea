package com.atenea.service.voice;

import com.atenea.api.core.CoreRequestContext;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.api.core.CoreCommandResponse;
import com.atenea.api.core.CoreCommandResultResponse;
import com.atenea.api.mobile.CreateMobileVoiceNoteRequest;
import com.atenea.api.mobile.CreateMobileVoiceNoteSendIntentRequest;
import com.atenea.api.mobile.MobileVoiceNoteSendConfirmResponse;
import com.atenea.api.mobile.MobileVoiceNoteSendIntentResponse;
import com.atenea.api.mobile.MobileVoiceFocusResponse;
import com.atenea.api.mobile.MobileVoiceCodexStatusResponse;
import com.atenea.api.mobile.MobileVoiceNoteResponse;
import com.atenea.api.mobile.MobileVoiceNotesResponse;
import com.atenea.api.mobile.MobileVoiceNotesSendResponse;
import com.atenea.api.mobile.MobileVoiceNotesStateResponse;
import com.atenea.api.mobile.MobileVoicePlaybackRequest;
import com.atenea.api.mobile.MobileVoicePlaybackResponse;
import com.atenea.api.mobile.SendMobileVoiceNotesRequest;
import com.atenea.api.mobile.UpdateMobileVoiceFocusRequest;
import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.CreateSessionTurnResponse;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.core.CoreChannel;
import com.atenea.persistence.core.CoreCommandEntity;
import com.atenea.persistence.core.CoreCommandRepository;
import com.atenea.persistence.core.CoreOperatorContextEntity;
import com.atenea.persistence.core.CoreTargetType;
import com.atenea.persistence.operations.ManagedHostEntity;
import com.atenea.persistence.operations.ManagedHostRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.voice.VoiceDomain;
import com.atenea.persistence.voice.VoiceFocusEntity;
import com.atenea.persistence.voice.VoiceFocusRepository;
import com.atenea.persistence.voice.VoiceNoteSendDestinationType;
import com.atenea.persistence.voice.VoiceNoteSendIntentEntity;
import com.atenea.persistence.voice.VoiceNoteSendIntentRepository;
import com.atenea.persistence.voice.VoiceNoteSendIntentStatus;
import com.atenea.persistence.voice.VoiceNoteEntity;
import com.atenea.persistence.voice.VoiceNoteRepository;
import com.atenea.persistence.voice.VoiceNoteStatus;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.core.CoreCommandService;
import com.atenea.service.core.CoreInvalidContextException;
import com.atenea.service.core.CoreOperatorContextService;
import com.atenea.service.worksession.SessionTurnService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceEngineService {

    private final OperatorRepository operatorRepository;
    private final ProjectRepository projectRepository;
    private final WorkSessionRepository workSessionRepository;
    private final CoreCommandRepository coreCommandRepository;
    private final ManagedHostRepository managedHostRepository;
    private final VoiceFocusRepository voiceFocusRepository;
    private final VoiceNoteRepository voiceNoteRepository;
    private final VoiceNoteSendIntentRepository voiceNoteSendIntentRepository;
    private final AgentRunRepository agentRunRepository;
    private final CoreCommandService coreCommandService;
    private final CoreOperatorContextService coreOperatorContextService;
    private final SessionTurnService sessionTurnService;
    private final ObjectMapper objectMapper;

    public VoiceEngineService(
            OperatorRepository operatorRepository,
            ProjectRepository projectRepository,
            WorkSessionRepository workSessionRepository,
            CoreCommandRepository coreCommandRepository,
            ManagedHostRepository managedHostRepository,
            VoiceFocusRepository voiceFocusRepository,
            VoiceNoteRepository voiceNoteRepository,
            VoiceNoteSendIntentRepository voiceNoteSendIntentRepository,
            AgentRunRepository agentRunRepository,
            CoreCommandService coreCommandService,
            CoreOperatorContextService coreOperatorContextService,
            SessionTurnService sessionTurnService,
            ObjectMapper objectMapper
    ) {
        this.operatorRepository = operatorRepository;
        this.projectRepository = projectRepository;
        this.workSessionRepository = workSessionRepository;
        this.coreCommandRepository = coreCommandRepository;
        this.managedHostRepository = managedHostRepository;
        this.voiceFocusRepository = voiceFocusRepository;
        this.voiceNoteRepository = voiceNoteRepository;
        this.voiceNoteSendIntentRepository = voiceNoteSendIntentRepository;
        this.agentRunRepository = agentRunRepository;
        this.coreCommandService = coreCommandService;
        this.coreOperatorContextService = coreOperatorContextService;
        this.sessionTurnService = sessionTurnService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MobileVoiceFocusResponse getFocus(AuthenticatedOperator authenticatedOperator) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        VoiceFocusEntity focus = voiceFocusRepository.findById(operator.getId()).orElse(null);
        focus = synchronizeStaleFocusFromCoreContext(operator, focus);
        int activeNoteCount = activeNotes(operator.getId()).size();
        if (focus == null || (focus.getProject() == null && focus.getWorkSession() == null && focus.getManagedHost() == null)) {
            MobileVoiceFocusResponse coreFocus = focusFromCoreOperatorContext(operator, activeNoteCount);
            if (coreFocus != null) {
                return coreFocus;
            }
        }
        return focus == null ? emptyFocus(operator, activeNoteCount) : toFocusResponse(focus, activeNoteCount);
    }

    private VoiceFocusEntity synchronizeStaleFocusFromCoreContext(OperatorEntity operator, VoiceFocusEntity existingFocus) {
        CoreOperatorContextEntity context = latestCoreContext(operator);
        if (context.getActiveWorkSessionId() == null && context.getActiveProjectId() == null) {
            return existingFocus;
        }
        if (existingFocus != null
                && context.getUpdatedAt() != null
                && existingFocus.getUpdatedAt() != null
                && !context.getUpdatedAt().isAfter(existingFocus.getUpdatedAt())) {
            return existingFocus;
        }
        if (!canSynchronizeFocus(existingFocus, context)) {
            return existingFocus;
        }

        Instant now = Instant.now();
        VoiceFocusEntity focus = existingFocus == null
                ? voiceFocusRepository.findById(operator.getId()).orElseGet(() -> {
                    VoiceFocusEntity created = new VoiceFocusEntity();
                    created.setOperator(operator);
                    created.setCreatedAt(now);
                    return created;
                })
                : existingFocus;
        focus.setOperator(operator);
        if (context.getActiveCommandId() != null) {
            coreCommandRepository.findById(context.getActiveCommandId()).ifPresent(command -> {
                focus.setActiveCommand(command);
                focus.setPlaybackSourceType("CORE_COMMAND");
                focus.setPlaybackSourceId(String.valueOf(context.getActiveCommandId()));
                focus.setPlaybackSegmentIndex(0);
                focus.setPlaybackSegmentCount(null);
            });
        }

        if (context.getActiveWorkSessionId() != null) {
            WorkSessionEntity session = requireWorkSession(context.getActiveWorkSessionId());
            focus.setDomain(VoiceDomain.DEVELOPMENT);
            focus.setProject(session.getProject());
            focus.setWorkSession(session);
            focus.setManagedHost(null);
            focus.setActivity("Conversacion activa");
        } else if (context.getActiveProjectId() != null) {
            ProjectEntity project = projectRepository.findById(context.getActiveProjectId()).orElse(null);
            if (project == null) {
                return existingFocus;
            }
            focus.setDomain(VoiceDomain.DEVELOPMENT);
            focus.setProject(project);
            focus.setWorkSession(null);
            focus.setManagedHost(null);
            focus.setActivity("Proyecto activo");
        }

        if (focus.getCreatedAt() == null) {
            focus.setCreatedAt(now);
        }
        focus.setUpdatedAt(now);
        return voiceFocusRepository.save(focus);
    }

    private boolean canSynchronizeFocus(VoiceFocusEntity focus, CoreOperatorContextEntity context) {
        if (focus == null) {
            return true;
        }
        if (focus.getManagedHost() != null || focus.getDomain() == VoiceDomain.OPERATIONS) {
            return false;
        }
        if (focus.getProject() == null && focus.getWorkSession() == null) {
            return true;
        }
        if (context.getActiveWorkSessionId() != null && focus.getWorkSession() != null) {
            return Objects.equals(focus.getWorkSession().getId(), context.getActiveWorkSessionId());
        }
        if (context.getActiveProjectId() != null && focus.getProject() != null) {
            return Objects.equals(focus.getProject().getId(), context.getActiveProjectId());
        }
        return false;
    }

    @Transactional
    public MobileVoiceFocusResponse updateFocus(
            AuthenticatedOperator authenticatedOperator,
            UpdateMobileVoiceFocusRequest request
    ) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        Instant now = Instant.now();
        VoiceFocusEntity focus = voiceFocusRepository.findById(operator.getId())
                .orElseGet(() -> {
                    VoiceFocusEntity created = new VoiceFocusEntity();
                    created.setOperator(operator);
                    created.setCreatedAt(now);
                    return created;
                });

        WorkSessionEntity workSession = request.workSessionId() == null ? null : requireWorkSession(request.workSessionId());
        ProjectEntity project = resolveProject(request.projectId(), workSession);

        focus.setOperator(operator);
        focus.setDomain(resolveDomain(request.domain(), project, workSession, request.managedHostId()));
        focus.setProject(project);
        focus.setWorkSession(workSession);
        focus.setActiveCommand(request.activeCommandId() == null ? null : requireCoreCommand(request.activeCommandId()));
        focus.setManagedHost(request.managedHostId() == null ? null : requireManagedHost(request.managedHostId()));
        focus.setActivity(blankToNull(request.activity()));
        applyPlayback(focus, request.playback());
        if (focus.getCreatedAt() == null) {
            focus.setCreatedAt(now);
        }
        focus.setUpdatedAt(now);

        VoiceFocusEntity saved = voiceFocusRepository.save(focus);
        return toFocusResponse(saved, activeNotes(operator.getId()).size());
    }

    @Transactional(readOnly = true)
    public MobileVoiceNotesResponse getActiveNotes(AuthenticatedOperator authenticatedOperator) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        return new MobileVoiceNotesResponse(activeNotes(operator.getId()).stream()
                .map(this::toNoteResponse)
                .toList());
    }

    @Transactional
    public MobileVoiceNotesStateResponse getNotesState(AuthenticatedOperator authenticatedOperator) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        List<VoiceNoteEntity> notes = activeNotes(operator.getId());
        VoiceFocusEntity focus = voiceFocusRepository.findById(operator.getId()).orElse(null);
        focus = synchronizeStaleFocusFromCoreContext(operator, focus);
        MobileVoiceFocusResponse focusResponse = focus == null
                ? emptyFocus(operator, notes.size())
                : toFocusResponse(focus, notes.size());
        return new MobileVoiceNotesStateResponse(
                focusResponse,
                notes.stream().map(this::toNoteResponse).toList(),
                activePendingSendIntent(operator.getId()).map(this::toSendIntentResponse).orElse(null));
    }

    @Transactional(readOnly = true)
    public MobileVoiceCodexStatusResponse getLatestCodexStatus(AuthenticatedOperator authenticatedOperator) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        Optional<VoiceNoteSendIntentEntity> latestSent = voiceNoteSendIntentRepository
                .findFirstByOperatorIdAndStatusOrderBySentAtDesc(operator.getId(), VoiceNoteSendIntentStatus.SENT);
        if (latestSent.isEmpty()) {
            return new MobileVoiceCodexStatusResponse(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    "No tengo envios recientes de notas a Codex desde voz.",
                    null);
        }

        VoiceNoteSendIntentEntity intent = latestSent.get();
        AgentRunEntity run = null;
        if (intent.getAgentRun() != null && intent.getAgentRun().getId() != null) {
            run = agentRunRepository.findWithSessionById(intent.getAgentRun().getId()).orElse(null);
        }
        AgentRunStatus status = run == null ? null : run.getStatus();
        boolean responseReady = status == AgentRunStatus.SUCCEEDED && run.getResultTurn() != null;
        boolean failed = status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELLED;
        String destination = destinationLabel(intent);
        String message;
        if (run == null) {
            message = "Tengo el envio registrado en " + destination + ", pero todavia no tengo una ejecucion de Codex asociada.";
        } else if (responseReady) {
            message = "Codex ya respondio en " + destination + ". Puedes pedirme que lea la ultima respuesta.";
        } else if (status == AgentRunStatus.RUNNING) {
            message = "Codex sigue trabajando en " + destination + ".";
        } else if (failed) {
            message = "Codex no pudo completar el trabajo en " + destination + ".";
        } else {
            message = "Codex esta en estado " + status + " para " + destination + ".";
        }

        Instant updatedAt = run == null
                ? intent.getUpdatedAt()
                : firstNonNull(run.getFinishedAt(), intent.getUpdatedAt());
        return new MobileVoiceCodexStatusResponse(
                intent.getProject() == null ? null : intent.getProject().getId(),
                intent.getProjectName(),
                intent.getWorkSession() == null ? null : intent.getWorkSession().getId(),
                intent.getWorkSessionTitle(),
                run == null ? null : run.getId(),
                status,
                responseReady,
                failed,
                message,
                updatedAt);
    }

    @Transactional
    public MobileVoiceNoteResponse createNote(
            AuthenticatedOperator authenticatedOperator,
            CreateMobileVoiceNoteRequest request
    ) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        String text = request.text().trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("Voice note text cannot be blank");
        }

        Instant now = Instant.now();
        VoiceNoteEntity note = new VoiceNoteEntity();
        note.setOperator(operator);
        note.setText(text);
        note.setStatus(VoiceNoteStatus.ACTIVE);
        note.setFocusSnapshotJson(focusSnapshotJson(operator.getId()));
        note.setCapturedAt(now);
        note.setCreatedAt(now);
        note.setUpdatedAt(now);
        return toNoteResponse(voiceNoteRepository.save(note));
    }

    @Transactional
    public MobileVoiceNoteResponse archiveNote(
            AuthenticatedOperator authenticatedOperator,
            Long noteId
    ) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        VoiceNoteEntity note = voiceNoteRepository.findByIdAndOperatorIdAndStatus(
                        noteId,
                        operator.getId(),
                        VoiceNoteStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Active voice note not found"));
        return toNoteResponse(archiveNote(note));
    }

    @Transactional
    public MobileVoiceNoteResponse archiveLastActiveNote(AuthenticatedOperator authenticatedOperator) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        VoiceNoteEntity note = voiceNoteRepository.findFirstByOperatorIdAndStatusOrderByCreatedAtDesc(
                        operator.getId(),
                        VoiceNoteStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("No active voice notes to archive"));
        return toNoteResponse(archiveNote(note));
    }

    @Transactional
    public MobileVoiceNotesResponse archiveActiveNotes(AuthenticatedOperator authenticatedOperator) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        List<VoiceNoteEntity> notes = activeNotes(operator.getId());
        List<MobileVoiceNoteResponse> archived = notes.stream()
                .map(this::archiveNote)
                .map(this::toNoteResponse)
                .toList();
        return new MobileVoiceNotesResponse(archived);
    }

    @Transactional
    public MobileVoiceNotesSendResponse sendActiveNotes(
            AuthenticatedOperator authenticatedOperator,
            SendMobileVoiceNotesRequest request
    ) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        List<VoiceNoteEntity> notes = activeNotes(operator.getId());
        if (notes.isEmpty()) {
            throw new IllegalArgumentException("No active voice notes to send");
        }

        VoiceFocusEntity focus = voiceFocusRepository.findById(operator.getId()).orElse(null);
        focus = synchronizeStaleFocusFromCoreContext(operator, focus);
        if (focus == null || focus.getWorkSession() == null) {
            throw new CoreInvalidContextException(
                    "No puedo enviar las notas a Codex porque el foco no apunta a una WorkSession activa");
        }
        if (focus.getProject() == null && focus.getWorkSession().getProject() != null) {
            focus.setProject(focus.getWorkSession().getProject());
            focus.setUpdatedAt(Instant.now());
            focus = voiceFocusRepository.save(focus);
        }
        CoreCommandResponse command = coreCommandService.createCommand(new CreateCoreCommandRequest(
                composePrompt(notes, request),
                CoreChannel.VOICE,
                new CoreRequestContext(
                        focus.getProject() == null ? null : focus.getProject().getId(),
                        focus.getWorkSession().getId(),
                        operator.getEmail(),
                        "SESSION"),
                null));
        CoreCommandEntity consumedCommand = coreCommandRepository.getReferenceById(command.commandId());
        synchronizeFocusAfterCommand(operator, focus, consumedCommand, command);
        Instant now = Instant.now();
        List<VoiceNoteEntity> consumedNotes = notes.stream()
                .peek(note -> {
                    note.setStatus(VoiceNoteStatus.SENT);
                    note.setConsumedByCommand(consumedCommand);
                    note.setConsumedAt(now);
                    note.setUpdatedAt(now);
                })
                .map(voiceNoteRepository::save)
                .toList();
        return new MobileVoiceNotesSendResponse(
                command,
                consumedNotes.stream().map(this::toNoteResponse).toList());
    }

    @Transactional
    public MobileVoiceNoteSendIntentResponse createSendIntent(
            AuthenticatedOperator authenticatedOperator,
            CreateMobileVoiceNoteSendIntentRequest request
    ) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        List<VoiceNoteEntity> notes = activeNotes(operator.getId());
        if (notes.isEmpty()) {
            throw new IllegalArgumentException("No hay notas activas para enviar");
        }

        activePendingSendIntent(operator.getId()).ifPresent(intent -> {
            Instant now = Instant.now();
            intent.setStatus(VoiceNoteSendIntentStatus.CANCELLED);
            intent.setCancelledAt(now);
            intent.setErrorMessage("Reemplazado por una nueva preparacion de envio");
            intent.setUpdatedAt(now);
            voiceNoteSendIntentRepository.save(intent);
        });

        VoiceFocusEntity focus = voiceFocusRepository.findById(operator.getId()).orElse(null);
        focus = synchronizeStaleFocusFromCoreContext(operator, focus);
        if (focus == null || focus.getWorkSession() == null) {
            throw new CoreInvalidContextException(
                    "No puedo preparar el envio de notas a Codex porque el foco no apunta a una WorkSession activa");
        }
        WorkSessionEntity session = focus.getWorkSession();
        ProjectEntity project = focus.getProject() == null ? session.getProject() : focus.getProject();
        String instruction = request == null ? null : blankToNull(request.instruction());
        Instant now = Instant.now();
        VoiceNoteSendIntentEntity intent = new VoiceNoteSendIntentEntity();
        intent.setOperator(operator);
        intent.setStatus(VoiceNoteSendIntentStatus.PENDING);
        intent.setDestinationType(VoiceNoteSendDestinationType.WORK_SESSION);
        intent.setProject(project);
        intent.setProjectName(project == null ? null : project.getName());
        intent.setWorkSession(session);
        intent.setWorkSessionTitle(session.getTitle());
        intent.setNoteIdsJson(writeJson(notes.stream().map(VoiceNoteEntity::getId).toList()));
        intent.setInstruction(instruction);
        intent.setConfirmationToken(UUID.randomUUID().toString());
        intent.setConfirmationPrompt(sendIntentPrompt(notes.size(), project, session));
        intent.setCreatedAt(now);
        intent.setExpiresAt(now.plus(10, ChronoUnit.MINUTES));
        intent.setUpdatedAt(now);
        return toSendIntentResponse(voiceNoteSendIntentRepository.save(intent));
    }

    @Transactional
    public MobileVoiceNoteSendConfirmResponse confirmSendIntent(
            AuthenticatedOperator authenticatedOperator,
            Long sendIntentId
    ) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        VoiceNoteSendIntentEntity intent = voiceNoteSendIntentRepository.findByIdAndOperatorId(sendIntentId, operator.getId())
                .orElseThrow(() -> new IllegalArgumentException("Envio de notas no encontrado"));
        ensurePendingIntent(intent);

        VoiceFocusEntity focus = voiceFocusRepository.findById(operator.getId()).orElse(null);
        focus = synchronizeStaleFocusFromCoreContext(operator, focus);
        Long focusedWorkSessionId = focus == null || focus.getWorkSession() == null ? null : focus.getWorkSession().getId();
        if (!Objects.equals(focusedWorkSessionId, intent.getWorkSession().getId())) {
            failIntent(intent, "No he enviado las notas porque el foco ya no apunta a "
                    + destinationLabel(intent) + ". Cambia el foco y vuelve a preparar el envio.");
        }

        List<Long> noteIds = noteIds(intent);
        List<VoiceNoteEntity> notes = activeNotes(operator.getId()).stream()
                .filter(note -> noteIds.contains(note.getId()))
                .sorted(Comparator.comparingInt(note -> noteIds.indexOf(note.getId())))
                .toList();
        if (notes.size() != noteIds.size()) {
            failIntent(intent, "No he enviado las notas porque alguna nota ya no esta activa.");
        }

        Instant now = Instant.now();
        intent.setStatus(VoiceNoteSendIntentStatus.CONFIRMED);
        intent.setConfirmedAt(now);
        intent.setUpdatedAt(now);
        voiceNoteSendIntentRepository.save(intent);

        CreateSessionTurnResponse turnResponse = sessionTurnService.createTurn(
                intent.getWorkSession().getId(),
                new CreateSessionTurnRequest(composeWorkSessionTurnPrompt(notes, intent)));

        Long runId = turnResponse.run() == null ? null : turnResponse.run().id();
        if (runId != null) {
            intent.setAgentRun(agentRunRepository.getReferenceById(runId));
        }
        intent.setStatus(VoiceNoteSendIntentStatus.SENT);
        intent.setSentAt(now);
        intent.setUpdatedAt(now);
        VoiceNoteSendIntentEntity savedIntent = voiceNoteSendIntentRepository.save(intent);

        List<VoiceNoteEntity> consumedNotes = notes.stream()
                .peek(note -> {
                    note.setStatus(VoiceNoteStatus.SENT);
                    note.setConsumedAt(now);
                    note.setUpdatedAt(now);
                })
                .map(voiceNoteRepository::save)
                .toList();

        Long operatorTurnId = turnResponse.operatorTurn() == null ? null : turnResponse.operatorTurn().id();
        String message = "Notas enviadas a Codex en " + destinationLabel(savedIntent) + ". Estoy esperando respuesta.";
        return new MobileVoiceNoteSendConfirmResponse(
                toSendIntentResponse(savedIntent),
                consumedNotes.stream().map(this::toNoteResponse).toList(),
                operatorTurnId,
                runId,
                message);
    }

    @Transactional
    public MobileVoiceNoteSendIntentResponse cancelSendIntent(
            AuthenticatedOperator authenticatedOperator,
            Long sendIntentId
    ) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        VoiceNoteSendIntentEntity intent = voiceNoteSendIntentRepository.findByIdAndOperatorId(sendIntentId, operator.getId())
                .orElseThrow(() -> new IllegalArgumentException("Envio de notas no encontrado"));
        ensurePendingIntent(intent);
        Instant now = Instant.now();
        intent.setStatus(VoiceNoteSendIntentStatus.CANCELLED);
        intent.setCancelledAt(now);
        intent.setUpdatedAt(now);
        return toSendIntentResponse(voiceNoteSendIntentRepository.save(intent));
    }

    private void synchronizeFocusAfterCommand(
            OperatorEntity operator,
            VoiceFocusEntity existingFocus,
            CoreCommandEntity activeCommand,
            CoreCommandResponse command
    ) {
        CoreCommandResultResponse result = command.result();
        if (result == null || result.targetType() == null || result.targetId() == null) {
            return;
        }

        Instant now = Instant.now();
        VoiceFocusEntity focus = existingFocus == null
                ? voiceFocusRepository.findById(operator.getId()).orElseGet(() -> {
                    VoiceFocusEntity created = new VoiceFocusEntity();
                    created.setOperator(operator);
                    created.setCreatedAt(now);
                    return created;
                })
                : existingFocus;
        focus.setOperator(operator);
        focus.setActiveCommand(activeCommand);
        focus.setUpdatedAt(now);
        if (focus.getCreatedAt() == null) {
            focus.setCreatedAt(now);
        }

        if (result.targetType() == CoreTargetType.WORK_SESSION) {
            WorkSessionEntity workSession = requireWorkSession(result.targetId());
            focus.setDomain(VoiceDomain.DEVELOPMENT);
            focus.setProject(workSession.getProject());
            focus.setWorkSession(workSession);
            focus.setManagedHost(null);
            focus.setActivity("Conversacion activa");
            voiceFocusRepository.save(focus);
            return;
        }

        if (result.targetType() == CoreTargetType.PROJECT) {
            ProjectEntity project = projectRepository.findById(result.targetId())
                    .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Voice command project target"));
            focus.setDomain(VoiceDomain.DEVELOPMENT);
            focus.setProject(project);
            focus.setWorkSession(null);
            focus.setManagedHost(null);
            focus.setActivity("Proyecto activo");
            voiceFocusRepository.save(focus);
        }
    }

    private OperatorEntity ensureActiveOperator(AuthenticatedOperator authenticatedOperator) {
        if (authenticatedOperator == null || authenticatedOperator.operatorId() == null) {
            throw new OperatorAuthenticationException("Operator account not found");
        }
        return operatorRepository.findById(authenticatedOperator.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(() -> new OperatorAuthenticationException("Operator account not found"));
    }

    private List<VoiceNoteEntity> activeNotes(Long operatorId) {
        return voiceNoteRepository.findByOperatorIdAndStatusOrderByCreatedAtAsc(operatorId, VoiceNoteStatus.ACTIVE);
    }

    private VoiceNoteEntity archiveNote(VoiceNoteEntity note) {
        Instant now = Instant.now();
        note.setStatus(VoiceNoteStatus.ARCHIVED);
        note.setConsumedAt(now);
        note.setUpdatedAt(now);
        return voiceNoteRepository.save(note);
    }

    private WorkSessionEntity requireWorkSession(Long workSessionId) {
        return workSessionRepository.findWithProjectById(workSessionId)
                .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Voice parameter: workSessionId"));
    }

    private ProjectEntity resolveProject(Long projectId, WorkSessionEntity workSession) {
        if (projectId == null && workSession != null) {
            return workSession.getProject();
        }
        if (projectId == null) {
            return null;
        }
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Voice parameter: projectId"));
        if (workSession != null && !workSession.getProject().getId().equals(project.getId())) {
            throw new CoreInvalidContextException("Voice focus project does not match work session");
        }
        return project;
    }

    private CoreCommandEntity requireCoreCommand(Long commandId) {
        return coreCommandRepository.findById(commandId)
                .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Voice parameter: activeCommandId"));
    }

    private ManagedHostEntity requireManagedHost(Long hostId) {
        return managedHostRepository.findByIdAndActiveTrue(hostId)
                .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Voice parameter: managedHostId"));
    }

    private VoiceDomain resolveDomain(
            VoiceDomain requestedDomain,
            ProjectEntity project,
            WorkSessionEntity workSession,
            Long managedHostId
    ) {
        if (requestedDomain != null) {
            return requestedDomain;
        }
        if (project != null || workSession != null) {
            return VoiceDomain.DEVELOPMENT;
        }
        if (managedHostId != null) {
            return VoiceDomain.OPERATIONS;
        }
        return VoiceDomain.NONE;
    }

    private void applyPlayback(VoiceFocusEntity focus, MobileVoicePlaybackRequest playback) {
        if (playback == null) {
            focus.setPlaybackSourceType(null);
            focus.setPlaybackSourceId(null);
            focus.setPlaybackSegmentIndex(null);
            focus.setPlaybackSegmentCount(null);
            return;
        }
        focus.setPlaybackSourceType(blankToNull(playback.sourceType()));
        focus.setPlaybackSourceId(blankToNull(playback.sourceId()));
        focus.setPlaybackSegmentIndex(playback.segmentIndex());
        focus.setPlaybackSegmentCount(playback.segmentCount());
    }

    private String focusSnapshotJson(Long operatorId) {
        VoiceFocusEntity focus = voiceFocusRepository.findById(operatorId).orElse(null);
        if (focus == null) {
            return writeJson(Map.of("domain", VoiceDomain.NONE.name()));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("domain", focus.getDomain().name());
        snapshot.put("projectId", focus.getProject() == null ? null : focus.getProject().getId());
        snapshot.put("projectName", focus.getProject() == null ? null : focus.getProject().getName());
        snapshot.put("workSessionId", focus.getWorkSession() == null ? null : focus.getWorkSession().getId());
        snapshot.put("workSessionTitle", focus.getWorkSession() == null ? null : focus.getWorkSession().getTitle());
        snapshot.put("activeCommandId", focus.getActiveCommand() == null ? null : focus.getActiveCommand().getId());
        snapshot.put("managedHostId", focus.getManagedHost() == null ? null : focus.getManagedHost().getId());
        snapshot.put("managedHostName", focus.getManagedHost() == null ? null : focus.getManagedHost().getName());
        snapshot.put("activity", focus.getActivity());
        snapshot.put("playbackSourceType", focus.getPlaybackSourceType());
        snapshot.put("playbackSourceId", focus.getPlaybackSourceId());
        snapshot.put("playbackSegmentIndex", focus.getPlaybackSegmentIndex());
        snapshot.put("playbackSegmentCount", focus.getPlaybackSegmentCount());
        snapshot.put("updatedAt", focus.getUpdatedAt());
        return writeJson(snapshot);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Voice focus snapshot could not be serialized", exception);
        }
    }

    private Optional<VoiceNoteSendIntentEntity> activePendingSendIntent(Long operatorId) {
        Optional<VoiceNoteSendIntentEntity> pending = voiceNoteSendIntentRepository
                .findFirstByOperatorIdAndStatusOrderByCreatedAtDesc(operatorId, VoiceNoteSendIntentStatus.PENDING);
        pending.ifPresent(intent -> {
            Instant now = Instant.now();
            if (intent.getExpiresAt() != null && intent.getExpiresAt().isBefore(now)) {
                intent.setStatus(VoiceNoteSendIntentStatus.EXPIRED);
                intent.setErrorMessage("Envio de notas caducado");
                intent.setUpdatedAt(now);
                voiceNoteSendIntentRepository.save(intent);
            }
        });
        return pending.filter(intent -> intent.getStatus() == VoiceNoteSendIntentStatus.PENDING);
    }

    private void ensurePendingIntent(VoiceNoteSendIntentEntity intent) {
        Instant now = Instant.now();
        if (intent.getStatus() != VoiceNoteSendIntentStatus.PENDING) {
            throw new IllegalArgumentException("El envio de notas ya no esta pendiente");
        }
        if (intent.getExpiresAt() != null && intent.getExpiresAt().isBefore(now)) {
            intent.setStatus(VoiceNoteSendIntentStatus.EXPIRED);
            intent.setErrorMessage("Envio de notas caducado");
            intent.setUpdatedAt(now);
            voiceNoteSendIntentRepository.save(intent);
            throw new IllegalArgumentException("El envio de notas ha caducado. Vuelve a prepararlo.");
        }
    }

    private void failIntent(VoiceNoteSendIntentEntity intent, String message) {
        Instant now = Instant.now();
        intent.setStatus(VoiceNoteSendIntentStatus.FAILED);
        intent.setErrorMessage(message);
        intent.setUpdatedAt(now);
        voiceNoteSendIntentRepository.save(intent);
        throw new CoreInvalidContextException(message);
    }

    private List<Long> noteIds(VoiceNoteSendIntentEntity intent) {
        try {
            return objectMapper.readValue(intent.getNoteIdsJson(), new TypeReference<List<Long>>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Voice note send intent note ids could not be parsed", exception);
        }
    }

    private String sendIntentPrompt(int noteCount, ProjectEntity project, WorkSessionEntity session) {
        return "Confirmacion requerida. Voy a enviar " + noteCount
                + " nota(s) a Codex en " + destinationLabel(project, session)
                + ". Di Atenea confirmo para enviarlas o Atenea cancela para no enviarlas.";
    }

    private String destinationLabel(VoiceNoteSendIntentEntity intent) {
        String projectName = intent.getProjectName();
        if (projectName == null && intent.getProject() != null) {
            projectName = intent.getProject().getName();
        }
        String sessionTitle = intent.getWorkSessionTitle();
        if (sessionTitle == null && intent.getWorkSession() != null) {
            sessionTitle = intent.getWorkSession().getTitle();
        }
        return destinationLabel(projectName, sessionTitle, intent.getWorkSession() == null ? null : intent.getWorkSession().getId());
    }

    private String destinationLabel(ProjectEntity project, WorkSessionEntity session) {
        return destinationLabel(
                project == null ? null : project.getName(),
                session == null ? null : session.getTitle(),
                session == null ? null : session.getId());
    }

    private String destinationLabel(String projectName, String sessionTitle, Long sessionId) {
        String project = blankToNull(projectName) == null ? "el proyecto activo" : projectName;
        String session = blankToNull(sessionTitle) == null ? "WorkSession " + sessionId : sessionTitle;
        return project + ", sesion " + session;
    }

    private String composeWorkSessionTurnPrompt(List<VoiceNoteEntity> notes, VoiceNoteSendIntentEntity intent) {
        StringBuilder prompt = new StringBuilder("Estas son mis notas de voz. Tratalas como instrucciones directas para esta WorkSession.");
        String instruction = blankToNull(intent.getInstruction());
        if (instruction != null) {
            prompt.append("\n\nInstruccion adicional: ").append(instruction);
        }
        prompt.append("\n\nNotas:");
        for (int index = 0; index < notes.size(); index++) {
            prompt.append("\n").append(index + 1).append(". ").append(notes.get(index).getText());
        }
        return prompt.toString();
    }

    private MobileVoiceNoteSendIntentResponse toSendIntentResponse(VoiceNoteSendIntentEntity intent) {
        List<Long> noteIds = noteIds(intent);
        return new MobileVoiceNoteSendIntentResponse(
                intent.getId(),
                intent.getStatus(),
                intent.getDestinationType(),
                intent.getProject() == null ? null : intent.getProject().getId(),
                intent.getProjectName(),
                intent.getWorkSession() == null ? null : intent.getWorkSession().getId(),
                intent.getWorkSessionTitle(),
                noteIds,
                noteIds.size(),
                intent.getInstruction(),
                intent.getConfirmationPrompt(),
                intent.getErrorMessage(),
                intent.getAgentRun() == null ? null : intent.getAgentRun().getId(),
                intent.getCreatedAt(),
                intent.getExpiresAt(),
                intent.getUpdatedAt());
    }

    private String composePrompt(List<VoiceNoteEntity> notes, SendMobileVoiceNotesRequest request) {
        StringBuilder prompt = new StringBuilder("Continua la WorkSession activa en Codex usando estas notas de voz como instrucciones del operador.");
        String instruction = request == null ? null : blankToNull(request.instruction());
        if (instruction != null) {
            prompt.append("\n\nInstruccion adicional: ").append(instruction);
        }
        prompt.append("\n\nNotas:");
        for (int index = 0; index < notes.size(); index++) {
            prompt.append("\n").append(index + 1).append(". ").append(notes.get(index).getText());
        }
        return prompt.toString();
    }

    private MobileVoiceFocusResponse emptyFocus(OperatorEntity operator, int activeNoteCount) {
        CoreOperatorContextEntity context = latestCoreContext(operator);
        return new MobileVoiceFocusResponse(
                operator.getId(),
                VoiceDomain.NONE,
                null,
                null,
                null,
                null,
                null,
                context.getActiveCommandId(),
                context.getActiveCommandId() == null,
                null,
                null,
                null,
                null,
                activeNoteCount,
                null);
    }

    private MobileVoiceFocusResponse focusFromCoreOperatorContext(OperatorEntity operator, int activeNoteCount) {
        var context = latestCoreContext(operator);

        if (context.getActiveWorkSessionId() != null) {
            WorkSessionEntity session = workSessionRepository.findWithProjectById(context.getActiveWorkSessionId())
                    .orElse(null);
            if (session != null) {
                return new MobileVoiceFocusResponse(
                        operator.getId(),
                        VoiceDomain.DEVELOPMENT,
                        session.getProject() == null ? null : session.getProject().getId(),
                        session.getProject() == null ? null : session.getProject().getName(),
                        session.getId(),
                        session.getTitle(),
                        context.getActiveCommandId(),
                        context.getActiveCommandId(),
                        true,
                        null,
                        null,
                        "Conversacion activa",
                        null,
                        activeNoteCount,
                        context.getUpdatedAt());
            }
        }

        if (context.getActiveProjectId() != null) {
            ProjectEntity project = projectRepository.findById(context.getActiveProjectId()).orElse(null);
            if (project != null) {
                return new MobileVoiceFocusResponse(
                        operator.getId(),
                        VoiceDomain.DEVELOPMENT,
                        project.getId(),
                        project.getName(),
                        null,
                        null,
                        context.getActiveCommandId(),
                        context.getActiveCommandId(),
                        true,
                        null,
                        null,
                        "Proyecto activo",
                        null,
                        activeNoteCount,
                        context.getUpdatedAt());
            }
        }

        return null;
    }

    private MobileVoiceFocusResponse toFocusResponse(VoiceFocusEntity focus, int activeNoteCount) {
        Long activeCommandId = focus.getActiveCommand() == null ? null : focus.getActiveCommand().getId();
        Long latestCommandId = latestCoreContext(focus.getOperator()).getActiveCommandId();
        return new MobileVoiceFocusResponse(
                focus.getOperator().getId(),
                focus.getDomain(),
                focus.getProject() == null ? null : focus.getProject().getId(),
                focus.getProject() == null ? null : focus.getProject().getName(),
                focus.getWorkSession() == null ? null : focus.getWorkSession().getId(),
                focus.getWorkSession() == null ? null : focus.getWorkSession().getTitle(),
                activeCommandId,
                latestCommandId,
                latestCommandId == null || Objects.equals(activeCommandId, latestCommandId),
                focus.getManagedHost() == null ? null : focus.getManagedHost().getId(),
                focus.getManagedHost() == null ? null : focus.getManagedHost().getName(),
                focus.getActivity(),
                new MobileVoicePlaybackResponse(
                        focus.getPlaybackSourceType(),
                        focus.getPlaybackSourceId(),
                        focus.getPlaybackSegmentIndex(),
                        focus.getPlaybackSegmentCount()),
                activeNoteCount,
                focus.getUpdatedAt());
    }

    private CoreOperatorContextEntity latestCoreContext(OperatorEntity operator) {
        CoreOperatorContextEntity context = coreOperatorContextService.getOrDefault(CoreOperatorContextService.DEFAULT_OPERATOR_KEY);
        if (context == null) {
            context = new CoreOperatorContextEntity();
            context.setOperatorKey(CoreOperatorContextService.DEFAULT_OPERATOR_KEY);
        }
        if (context.getActiveProjectId() == null
                && context.getActiveWorkSessionId() == null
                && context.getActiveCommandId() == null
                && operator.getEmail() != null) {
            CoreOperatorContextEntity operatorContext = coreOperatorContextService.getOrDefault(operator.getEmail());
            if (operatorContext != null) {
                context = operatorContext;
            }
        }
        return context;
    }

    private MobileVoiceNoteResponse toNoteResponse(VoiceNoteEntity note) {
        return new MobileVoiceNoteResponse(
                note.getId(),
                note.getText(),
                note.getStatus(),
                note.getFocusSnapshotJson(),
                note.getConsumedByCommand() == null ? null : note.getConsumedByCommand().getId(),
                note.getCapturedAt(),
                note.getConsumedAt(),
                note.getUpdatedAt());
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Instant firstNonNull(Instant first, Instant second) {
        return first == null ? second : first;
    }
}
