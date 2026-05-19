package com.atenea.api.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.service.core.CoreSpeechAudioResponse;
import com.atenea.service.core.CoreSpeechSynthesisService;
import com.atenea.service.voice.MobileVoiceRealtimeSessionService;
import com.atenea.service.voice.VoiceCommandTelemetryService;
import com.atenea.service.voice.VoiceEngineService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/voice")
public class MobileVoiceController {

    private final VoiceEngineService voiceEngineService;
    private final CoreSpeechSynthesisService coreSpeechSynthesisService;
    private final MobileVoiceRealtimeSessionService mobileVoiceRealtimeSessionService;
    private final VoiceCommandTelemetryService voiceCommandTelemetryService;

    public MobileVoiceController(
            VoiceEngineService voiceEngineService,
            CoreSpeechSynthesisService coreSpeechSynthesisService,
            MobileVoiceRealtimeSessionService mobileVoiceRealtimeSessionService,
            VoiceCommandTelemetryService voiceCommandTelemetryService
    ) {
        this.voiceEngineService = voiceEngineService;
        this.coreSpeechSynthesisService = coreSpeechSynthesisService;
        this.mobileVoiceRealtimeSessionService = mobileVoiceRealtimeSessionService;
        this.voiceCommandTelemetryService = voiceCommandTelemetryService;
    }

    @GetMapping("/focus")
    public MobileVoiceFocusResponse getFocus(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return voiceEngineService.getFocus(operator);
    }

    @PostMapping("/focus")
    public MobileVoiceFocusResponse updateFocus(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody UpdateMobileVoiceFocusRequest request
    ) {
        return voiceEngineService.updateFocus(operator, request);
    }

    @GetMapping("/notes/active")
    public MobileVoiceNotesResponse getActiveNotes(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return voiceEngineService.getActiveNotes(operator);
    }

    @GetMapping("/notes/state")
    public MobileVoiceNotesStateResponse getNotesState(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return voiceEngineService.getNotesState(operator);
    }

    @GetMapping("/codex/latest-status")
    public MobileVoiceCodexStatusResponse getLatestCodexStatus(
            @AuthenticationPrincipal AuthenticatedOperator operator
    ) {
        return voiceEngineService.getLatestCodexStatus(operator);
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public MobileVoiceNoteResponse createNote(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody CreateMobileVoiceNoteRequest request
    ) {
        return voiceEngineService.createNote(operator, request);
    }

    @PostMapping("/notes/{noteId}/archive")
    public MobileVoiceNoteResponse archiveNote(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable Long noteId
    ) {
        return voiceEngineService.archiveNote(operator, noteId);
    }

    @PostMapping("/notes/archive-last")
    public MobileVoiceNoteResponse archiveLastNote(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return voiceEngineService.archiveLastActiveNote(operator);
    }

    @PostMapping("/notes/archive-active")
    public MobileVoiceNotesResponse archiveActiveNotes(@AuthenticationPrincipal AuthenticatedOperator operator) {
        return voiceEngineService.archiveActiveNotes(operator);
    }

    @PostMapping("/notes/send")
    @ResponseStatus(HttpStatus.CREATED)
    public MobileVoiceNotesSendResponse sendActiveNotes(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody(required = false) SendMobileVoiceNotesRequest request
    ) {
        return voiceEngineService.sendActiveNotes(operator, request);
    }

    @PostMapping("/notes/send-intents")
    @ResponseStatus(HttpStatus.CREATED)
    public MobileVoiceNoteSendIntentResponse createNoteSendIntent(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody(required = false) CreateMobileVoiceNoteSendIntentRequest request
    ) {
        return voiceEngineService.createSendIntent(operator, request);
    }

    @PostMapping("/notes/send-intents/{sendIntentId}/confirm")
    public MobileVoiceNoteSendConfirmResponse confirmNoteSendIntent(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable Long sendIntentId
    ) {
        return voiceEngineService.confirmSendIntent(operator, sendIntentId);
    }

    @PostMapping("/notes/send-intents/{sendIntentId}/cancel")
    public MobileVoiceNoteSendIntentResponse cancelNoteSendIntent(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable Long sendIntentId
    ) {
        return voiceEngineService.cancelSendIntent(operator, sendIntentId);
    }

    @PostMapping("/speech")
    public ResponseEntity<byte[]> createSpeech(
            @Valid @RequestBody CreateMobileVoiceSpeechRequest request
    ) {
        CoreSpeechAudioResponse response = coreSpeechSynthesisService.synthesizeText(
                request.text().trim(),
                request.voice(),
                request.speed());
        return ResponseEntity.ok()
                .contentType(response.mediaType())
                .body(response.audio());
    }

    @PostMapping("/realtime/session")
    public MobileVoiceRealtimeSessionResponse createRealtimeSession(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestBody(required = false) CreateMobileVoiceRealtimeSessionRequest request
    ) {
        MobileVoiceFocusResponse focus = voiceEngineService.getFocus(operator);
        MobileVoiceNotesResponse notes = voiceEngineService.getActiveNotes(operator);
        return mobileVoiceRealtimeSessionService.createSession(
                realtimeOperatorContext(focus, notes, request),
                request == null ? null : request.voice(),
                request == null ? null : request.speed());
    }

    @PostMapping("/command-telemetry")
    @ResponseStatus(HttpStatus.CREATED)
    public MobileVoiceCommandTelemetryResponse recordCommandTelemetry(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody RecordMobileVoiceCommandTelemetryRequest request
    ) {
        return voiceCommandTelemetryService.record(operator, request);
    }

    @GetMapping("/command-telemetry")
    public MobileVoiceCommandTelemetryListResponse getRecentCommandTelemetry(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestParam(required = false) Integer limit
    ) {
        return voiceCommandTelemetryService.recent(operator, limit);
    }

    @GetMapping("/command-telemetry/summary")
    public MobileVoiceCommandTelemetrySummaryResponse getCommandTelemetrySummary(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestParam(required = false) Integer limit
    ) {
        return voiceCommandTelemetryService.summary(operator, limit);
    }

    private String realtimeOperatorContext(
            MobileVoiceFocusResponse focus,
            MobileVoiceNotesResponse notes,
            CreateMobileVoiceRealtimeSessionRequest request
    ) {
        StringBuilder context = new StringBuilder("Este contexto viene del backend Atenea al crear el token efimero Realtime.\n");
        if (focus == null) {
            context.append("Foco: no cargado.\n");
        } else {
            context.append("Dominio: ").append(focus.domain()).append(".\n");
            append(context, "Project ID", focus.projectId());
            append(context, "Proyecto", focus.projectName());
            append(context, "WorkSession ID", focus.workSessionId());
            append(context, "WorkSession", focus.workSessionTitle());
            append(context, "Comando activo ID", focus.activeCommandId());
            append(context, "Ultimo comando Core ID", focus.latestCommandId());
            append(context, "Foco al dia", focus.focusUpToDate());
            append(context, "Managed host ID", focus.managedHostId());
            append(context, "Servidor", focus.managedHostName());
            append(context, "Actividad", focus.activity());
            if (focus.playback() != null) {
                context.append("Cursor persistido: ")
                        .append(focus.playback().sourceType()).append("/")
                        .append(focus.playback().sourceId()).append(", segmento ")
                        .append(focus.playback().segmentIndex() == null ? "desconocido" : focus.playback().segmentIndex() + 1)
                        .append(" de ")
                        .append(focus.playback().segmentCount() == null ? "desconocido" : focus.playback().segmentCount())
                        .append(".\n");
            }
            context.append("Notas activas: ").append(focus.activeNoteCount()).append(".\n");
        }
        if (notes != null && notes.notes() != null && !notes.notes().isEmpty()) {
            context.append("Notas activas transcritas:\n");
            for (int index = 0; index < Math.min(5, notes.notes().size()); index++) {
                MobileVoiceNoteResponse note = notes.notes().get(index);
                context.append(index + 1).append(". ").append(limit(note.text(), 220)).append("\n");
            }
        }
        if (request != null && request.clientContext() != null && !request.clientContext().isBlank()) {
            context.append("\nContexto local del cliente Android:\n")
                    .append(limit(request.clientContext().trim(), 2_500))
                    .append("\n");
        }
        return context.toString().trim();
    }

    private void append(StringBuilder builder, String label, Object value) {
        if (value != null && !value.toString().isBlank()) {
            builder.append(label).append(": ").append(value).append(".\n");
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
