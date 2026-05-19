package com.atenea.service.voice;

import com.atenea.api.mobile.MobileVoiceCommandTelemetryListResponse;
import com.atenea.api.mobile.MobileVoiceCommandTelemetryResponse;
import com.atenea.api.mobile.RecordMobileVoiceCommandTelemetryRequest;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.voice.VoiceCommandTelemetryEntity;
import com.atenea.persistence.voice.VoiceCommandTelemetryRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceCommandTelemetryService {

    private static final int MAX_RECENT_ITEMS = 200;

    private final OperatorRepository operatorRepository;
    private final VoiceCommandTelemetryRepository telemetryRepository;

    public VoiceCommandTelemetryService(
            OperatorRepository operatorRepository,
            VoiceCommandTelemetryRepository telemetryRepository
    ) {
        this.operatorRepository = operatorRepository;
        this.telemetryRepository = telemetryRepository;
    }

    @Transactional
    public MobileVoiceCommandTelemetryResponse record(
            AuthenticatedOperator authenticatedOperator,
            RecordMobileVoiceCommandTelemetryRequest request
    ) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        Instant now = Instant.now();
        VoiceCommandTelemetryEntity entity = new VoiceCommandTelemetryEntity();
        entity.setOperator(operator);
        entity.setClientEventId(limit(blankToNull(request.clientEventId()), 80));
        entity.setSource(limit(defaultText(request.source(), "android_realtime"), 40));
        entity.setOutcome(limit(defaultText(request.outcome(), "UNRECOGNIZED"), 40));
        entity.setReason(limit(defaultText(request.reason(), "unknown"), 120));
        entity.setTranscript(limit(request.transcript().trim(), 4_000));
        entity.setNormalizedTranscript(limit(blankToNull(request.normalizedTranscript()), 4_000));
        entity.setWakeWordDetected(request.wakeWordDetected());
        entity.setStartsWithWakeWord(request.startsWithWakeWord());
        entity.setIntentType(limit(blankToNull(request.intentType()), 120));
        entity.setDomain(limit(blankToNull(request.domain()), 32));
        entity.setProjectId(request.projectId());
        entity.setProjectName(limit(blankToNull(request.projectName()), 160));
        entity.setWorkSessionId(request.workSessionId());
        entity.setWorkSessionTitle(limit(blankToNull(request.workSessionTitle()), 220));
        entity.setActiveCommandId(request.activeCommandId());
        entity.setActiveNoteCount(request.activeNoteCount());
        entity.setPendingSendIntentId(request.pendingSendIntentId());
        entity.setRealtimeConnected(request.realtimeConnected());
        entity.setVoiceState(limit(blankToNull(request.voiceState()), 80));
        entity.setCreatedAt(now);
        return toResponse(telemetryRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public MobileVoiceCommandTelemetryListResponse recent(
            AuthenticatedOperator authenticatedOperator,
            Integer limit
    ) {
        OperatorEntity operator = ensureActiveOperator(authenticatedOperator);
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, MAX_RECENT_ITEMS));
        return new MobileVoiceCommandTelemetryListResponse(
                telemetryRepository.findByOperatorIdOrderByCreatedAtDesc(operator.getId()).stream()
                        .limit(safeLimit)
                        .map(this::toResponse)
                        .toList());
    }

    private OperatorEntity ensureActiveOperator(AuthenticatedOperator authenticatedOperator) {
        if (authenticatedOperator == null || authenticatedOperator.operatorId() == null) {
            throw new OperatorAuthenticationException("Operator account not found");
        }
        return operatorRepository.findById(authenticatedOperator.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(() -> new OperatorAuthenticationException("Operator account not found"));
    }

    private MobileVoiceCommandTelemetryResponse toResponse(VoiceCommandTelemetryEntity entity) {
        return new MobileVoiceCommandTelemetryResponse(
                entity.getId(),
                entity.getClientEventId(),
                entity.getSource(),
                entity.getOutcome(),
                entity.getReason(),
                entity.getTranscript(),
                entity.getNormalizedTranscript(),
                entity.isWakeWordDetected(),
                entity.isStartsWithWakeWord(),
                entity.getIntentType(),
                entity.getDomain(),
                entity.getProjectId(),
                entity.getProjectName(),
                entity.getWorkSessionId(),
                entity.getWorkSessionTitle(),
                entity.getActiveCommandId(),
                entity.getActiveNoteCount(),
                entity.getPendingSendIntentId(),
                entity.getRealtimeConnected(),
                entity.getVoiceState(),
                entity.getCreatedAt());
    }

    private String defaultText(String value, String fallback) {
        String text = blankToNull(value);
        return text == null ? fallback : text;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
