package com.atenea.service.core;

import com.atenea.api.core.CoreCommandResponse;
import com.atenea.api.core.CoreCommandDetailResponse;
import com.atenea.api.core.CoreCommandEventsResponse;
import com.atenea.api.core.CoreCommandListResponse;
import com.atenea.api.core.CoreCommandSummaryResponse;
import com.atenea.api.core.CoreClarificationOptionResponse;
import com.atenea.api.core.CoreClarificationResponse;
import com.atenea.api.core.CoreConfirmationResponse;
import com.atenea.api.core.ConfirmCoreCommandRequest;
import com.atenea.api.core.CoreInterpretationResponse;
import com.atenea.api.core.CoreCommandResultResponse;
import com.atenea.api.core.CoreConfirmationRequest;
import com.atenea.api.core.CoreIntentResponse;
import com.atenea.api.core.CoreRequestContext;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreCommandEntity;
import com.atenea.persistence.core.CoreCommandRepository;
import com.atenea.persistence.core.CoreCommandEventPhase;
import com.atenea.persistence.core.CoreCommandStatus;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreInterpreterSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Sort;

@Service
public class CoreCommandService {

    private final CoreCommandRepository coreCommandRepository;
    private final CoreIntentInterpreter coreIntentInterpreter;
    private final CoreCapabilityRegistry coreCapabilityRegistry;
    private final CoreDomainRouter coreDomainRouter;
    private final CoreOperatorContextService coreOperatorContextService;
    private final CoreCommandEventService coreCommandEventService;
    private final ObjectMapper objectMapper;

    public CoreCommandService(
            CoreCommandRepository coreCommandRepository,
            CoreIntentInterpreter coreIntentInterpreter,
            CoreCapabilityRegistry coreCapabilityRegistry,
            CoreDomainRouter coreDomainRouter,
            CoreOperatorContextService coreOperatorContextService,
            CoreCommandEventService coreCommandEventService,
            ObjectMapper objectMapper
    ) {
        this.coreCommandRepository = coreCommandRepository;
        this.coreIntentInterpreter = coreIntentInterpreter;
        this.coreCapabilityRegistry = coreCapabilityRegistry;
        this.coreDomainRouter = coreDomainRouter;
        this.coreOperatorContextService = coreOperatorContextService;
        this.coreCommandEventService = coreCommandEventService;
        this.objectMapper = objectMapper;
    }

    public CoreCommandResponse createCommand(CreateCoreCommandRequest request) {
        CreateCoreCommandRequest effectiveRequest = coreOperatorContextService.applyActiveContext(request);
        CoreCommandEntity command = initializeCommand(effectiveRequest);
        coreCommandRepository.save(command);
        coreCommandEventService.record(command.getId(), CoreCommandEventPhase.RECEIVED, "Core command received", null);

        CoreIntentEnvelope intent = null;
        try {
            coreCommandEventService.record(
                    command.getId(),
                    CoreCommandEventPhase.RESOLVING_CONTEXT,
                    "Resolved effective operator context",
                    effectiveRequest.context());
            coreCommandEventService.record(command.getId(), CoreCommandEventPhase.INTERPRETING, "Interpreting command intent", null);
            CoreInterpretationResult interpretation = coreIntentInterpreter.interpret(effectiveRequest);
            CoreIntentProposal proposal = interpretation.proposal();
            CapabilityDefinition capabilityDefinition = coreCapabilityRegistry.requireEnabled(
                    proposal.domain(),
                    proposal.capability());
            intent = new CoreIntentEnvelope(
                    proposal.intent(),
                    proposal.domain(),
                    proposal.capability(),
                    proposal.parameters(),
                    proposal.confidence(),
                    capabilityDefinition.riskLevel(),
                    capabilityDefinition.requiresConfirmation());
            applyIntent(command, intent, interpretation);

            if (intent.requiresConfirmation() && !isConfirmed(effectiveRequest.confirmation())) {
                return markNeedsConfirmation(command, intent);
            }

            coreCommandEventService.record(command.getId(), CoreCommandEventPhase.EXECUTING, "Executing routed core capability", intent);
            CoreCommandExecutionResult result = coreDomainRouter.execute(intent, buildExecutionContext(command, effectiveRequest));
            return markSucceeded(command, effectiveRequest, intent, result);
        } catch (CoreClarificationRequiredException exception) {
            return markNeedsClarification(command, intent, exception.clarification());
        } catch (CoreCommandRejectedException exception) {
            markRejected(command, intent, exception);
            throw exception;
        } catch (RuntimeException exception) {
            markFailed(command, intent, exception);
            throw exception;
        }
    }

    public CoreCommandResponse confirmCommand(Long commandId, ConfirmCoreCommandRequest request) {
        CoreCommandEntity command = coreCommandRepository.findById(commandId)
                .orElseThrow(() -> new CoreCommandNotFoundException(commandId));

        if (command.getStatus() != CoreCommandStatus.NEEDS_CONFIRMATION) {
            throw new CoreCommandConfirmationNotPendingException(commandId);
        }
        if (command.getConfirmationToken() == null || !command.getConfirmationToken().equals(request.confirmationToken())) {
            throw new CoreCommandConfirmationTokenMismatchException(commandId);
        }

        CoreIntentEnvelope intent = restoreIntentEnvelope(command);
        CapabilityDefinition capabilityDefinition = coreCapabilityRegistry.requireEnabled(
                intent.domain(),
                intent.capability());
        CoreIntentEnvelope confirmedIntent = new CoreIntentEnvelope(
                intent.intent(),
                intent.domain(),
                intent.capability(),
                intent.parameters(),
                intent.confidence(),
                capabilityDefinition.riskLevel(),
                capabilityDefinition.requiresConfirmation());

        command.setConfirmed(true);
        command.setRequiresConfirmation(confirmedIntent.requiresConfirmation());
        command.setRiskLevel(confirmedIntent.riskLevel());
        command.setConfidence(confirmedIntent.confidence());
        command.setParametersJson(writeJson(confirmedIntent.parameters()));
        command.setInterpretedIntentJson(writeJson(confirmedIntent));
        command.setErrorCode(null);
        command.setErrorMessage(null);
        command.setResultType(null);
        command.setTargetType(null);
        command.setTargetId(null);
        command.setResultSummary(null);
        command.setOperatorMessage(null);
        command.setFinishedAt(null);
        coreCommandRepository.save(command);

        try {
            coreCommandEventService.record(
                    command.getId(),
                    CoreCommandEventPhase.EXECUTING,
                    "Executing confirmed core capability",
                    confirmedIntent);
            CoreCommandExecutionResult result = coreDomainRouter.execute(
                    confirmedIntent,
                    buildExecutionContext(command, confirmedIntent, request.confirmationToken()));
            return markSucceeded(command, new CreateCoreCommandRequest(
                    command.getRawInput(),
                    command.getChannel(),
                    readRequestContext(command.getRequestContextJson()),
                    new CoreConfirmationRequest(true, request.confirmationToken())),
                    confirmedIntent,
                    result);
        } catch (CoreCommandRejectedException exception) {
            markRejected(command, confirmedIntent, exception);
            throw exception;
        } catch (RuntimeException exception) {
            markFailed(command, confirmedIntent, exception);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public CoreCommandListResponse getCommands(
            CoreCommandStatus status,
            CoreDomain domain,
            CoreInterpreterSource interpreterSource,
            String query
    ) {
        String normalizedQuery = normalizeNullableText(query);
        return new CoreCommandListResponse(coreCommandRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .filter(command -> status == null || command.getStatus() == status)
                .filter(command -> domain == null || command.getDomain() == domain)
                .filter(command -> interpreterSource == null || command.getInterpreterSource() == interpreterSource)
                .filter(command -> matchesQuery(command, normalizedQuery))
                .map(this::toSummaryResponse)
                .toList());
    }

    @Transactional(readOnly = true)
    public CoreCommandDetailResponse getCommand(Long commandId) {
        CoreCommandEntity command = coreCommandRepository.findById(commandId)
                .orElseThrow(() -> new CoreCommandNotFoundException(commandId));
        return toDetailResponse(command);
    }

    @Transactional(readOnly = true)
    public CoreCommandEventsResponse getCommandEvents(Long commandId) {
        if (!coreCommandRepository.existsById(commandId)) {
            throw new CoreCommandNotFoundException(commandId);
        }
        return coreCommandEventService.getEvents(commandId);
    }

    private CoreCommandEntity initializeCommand(CreateCoreCommandRequest request) {
        Instant now = Instant.now();
        CoreCommandEntity command = new CoreCommandEntity();
        command.setRawInput(request.input().trim());
        command.setChannel(request.channel());
        command.setStatus(CoreCommandStatus.RECEIVED);
        command.setDomain(null);
        command.setIntent(null);
        command.setCapability(null);
        command.setRiskLevel(null);
        command.setRequiresConfirmation(false);
        command.setConfirmationToken(request.confirmation() == null ? null : request.confirmation().confirmationToken());
        command.setConfirmed(isConfirmed(request.confirmation()));
        command.setConfidence(null);
        command.setRequestContextJson(writeJson(request.context()));
        command.setParametersJson(null);
        command.setInterpretedIntentJson(null);
        command.setClarificationJson(null);
        command.setResultType(null);
        command.setTargetType(null);
        command.setTargetId(null);
        command.setResultSummary(null);
        command.setErrorCode(null);
        command.setErrorMessage(null);
        command.setOperatorMessage(null);
        command.setSpeakableMessage(null);
        command.setInterpreterSource(null);
        command.setInterpreterDetail(null);
        command.setCreatedAt(now);
        command.setFinishedAt(null);
        return command;
    }

    private void applyIntent(
            CoreCommandEntity command,
            CoreIntentEnvelope intent,
            CoreInterpretationResult interpretation
    ) {
        command.setDomain(intent.domain());
        command.setIntent(intent.intent());
        command.setCapability(intent.capability());
        command.setRiskLevel(intent.riskLevel());
        command.setRequiresConfirmation(intent.requiresConfirmation());
        command.setConfidence(intent.confidence());
        command.setInterpreterSource(interpretation.source());
        command.setInterpreterDetail(interpretation.detail());
        command.setParametersJson(writeJson(intent.parameters()));
        command.setInterpretedIntentJson(writeJson(intent));
        command.setClarificationJson(null);
        coreCommandRepository.save(command);
    }

    private CoreCommandResponse markNeedsClarification(
            CoreCommandEntity command,
            CoreIntentEnvelope intent,
            CoreClarification clarification
    ) {
        if (intent != null) {
            command.setDomain(intent.domain());
            command.setIntent(intent.intent());
            command.setCapability(intent.capability());
            command.setRiskLevel(intent.riskLevel());
            command.setRequiresConfirmation(intent.requiresConfirmation());
            command.setConfidence(intent.confidence());
        }
        command.setStatus(CoreCommandStatus.NEEDS_CLARIFICATION);
        command.setClarificationJson(writeJson(clarification));
        command.setOperatorMessage(clarification.message());
        command.setSpeakableMessage(clarification.message());
        command.setFinishedAt(Instant.now());
        coreCommandRepository.save(command);
        coreCommandEventService.record(
                command.getId(),
                CoreCommandEventPhase.NEEDS_CLARIFICATION,
                clarification.message(),
                clarification);
        return toResponse(command, intent, null);
    }

    private CoreCommandResponse markNeedsConfirmation(CoreCommandEntity command, CoreIntentEnvelope intent) {
        command.setStatus(CoreCommandStatus.NEEDS_CONFIRMATION);
        if (command.getConfirmationToken() == null || command.getConfirmationToken().isBlank()) {
            command.setConfirmationToken(UUID.randomUUID().toString());
        }
        command.setOperatorMessage("Esta capacidad requiere confirmación explícita antes de ejecutarse.");
        command.setSpeakableMessage("Necesito tu confirmación antes de ejecutar esta acción.");
        command.setFinishedAt(Instant.now());
        coreCommandRepository.save(command);
        coreCommandEventService.record(
                command.getId(),
                CoreCommandEventPhase.NEEDS_CONFIRMATION,
                "Core command is waiting for explicit confirmation",
                Map.of("confirmationToken", command.getConfirmationToken()));
        return toResponse(command, intent, null);
    }

    private CoreCommandResponse markSucceeded(
            CoreCommandEntity command,
            CreateCoreCommandRequest request,
            CoreIntentEnvelope intent,
            CoreCommandExecutionResult result
    ) {
        command.setStatus(CoreCommandStatus.SUCCEEDED);
        command.setClarificationJson(null);
        command.setResultType(result.resultType());
        command.setTargetType(result.targetType());
        command.setTargetId(result.targetId());
        command.setResultSummary(result.resultSummary());
        command.setOperatorMessage(result.operatorMessage());
        command.setSpeakableMessage(result.speakableMessage() == null ? result.operatorMessage() : result.speakableMessage());
        command.setFinishedAt(Instant.now());
        coreCommandRepository.save(command);
        coreOperatorContextService.updateAfterSuccess(
                coreOperatorContextService.resolveOperatorKey(request.context()),
                command.getId(),
                intent,
                result);
        coreCommandEventService.record(command.getId(), CoreCommandEventPhase.SUCCEEDED, result.operatorMessage(), result.payload());
        return toResponse(command, intent, new CoreCommandResultResponse(
                result.resultType(),
                result.targetType(),
                result.targetId(),
                result.payload()));
    }

    private void markRejected(
            CoreCommandEntity command,
            CoreIntentEnvelope intent,
            CoreCommandRejectedException exception
    ) {
        if (intent != null) {
            command.setDomain(intent.domain());
            command.setIntent(intent.intent());
            command.setCapability(intent.capability());
            command.setRiskLevel(intent.riskLevel());
            command.setRequiresConfirmation(intent.requiresConfirmation());
            command.setConfidence(intent.confidence());
        }
        command.setStatus(CoreCommandStatus.REJECTED);
        command.setClarificationJson(null);
        command.setErrorCode(exception.getCode());
        command.setErrorMessage(exception.getMessage());
        command.setOperatorMessage(exception.getMessage());
        command.setSpeakableMessage(exception.getMessage());
        command.setFinishedAt(Instant.now());
        coreCommandRepository.save(command);
        coreCommandEventService.record(command.getId(), CoreCommandEventPhase.REJECTED, exception.getMessage(), null);
    }

    private void markFailed(CoreCommandEntity command, CoreIntentEnvelope intent, RuntimeException exception) {
        if (intent != null) {
            command.setDomain(intent.domain());
            command.setIntent(intent.intent());
            command.setCapability(intent.capability());
            command.setRiskLevel(intent.riskLevel());
            command.setRequiresConfirmation(intent.requiresConfirmation());
            command.setConfidence(intent.confidence());
        }
        command.setStatus(CoreCommandStatus.FAILED);
        command.setClarificationJson(null);
        command.setErrorCode("EXECUTION_FAILED");
        command.setErrorMessage(exception.getMessage());
        command.setOperatorMessage("El comando de Atenea Core ha fallado durante la ejecución.");
        command.setSpeakableMessage("El comando ha fallado durante la ejecución.");
        command.setFinishedAt(Instant.now());
        coreCommandRepository.save(command);
        coreCommandEventService.record(command.getId(), CoreCommandEventPhase.FAILED, command.getOperatorMessage(), null);
    }

    private CoreExecutionContext buildExecutionContext(CoreCommandEntity command, CreateCoreCommandRequest request) {
        CoreRequestContext context = request.context();
        CoreConfirmationRequest confirmation = request.confirmation();
        return new CoreExecutionContext(
                command.getId(),
                context == null ? null : context.projectId(),
                context == null ? null : context.workSessionId(),
                coreOperatorContextService.resolveOperatorKey(context),
                isConfirmed(confirmation),
                confirmation == null ? null : confirmation.confirmationToken());
    }

    private CoreExecutionContext buildExecutionContext(
            CoreCommandEntity command,
            CoreIntentEnvelope intent,
            String confirmationToken
    ) {
        Map<String, Object> parameters = intent.parameters();
        Long projectId = parameters.get("projectId") instanceof Number number ? number.longValue() : null;
        Long workSessionId = parameters.get("workSessionId") instanceof Number number ? number.longValue() : null;
        return new CoreExecutionContext(
                command.getId(),
                projectId,
                workSessionId,
                readRequestContext(command.getRequestContextJson()) == null
                        ? CoreOperatorContextService.DEFAULT_OPERATOR_KEY
                        : coreOperatorContextService.resolveOperatorKey(readRequestContext(command.getRequestContextJson())),
                true,
                confirmationToken);
    }

    private CoreCommandResponse toResponse(
            CoreCommandEntity command,
            CoreIntentEnvelope intent,
            CoreCommandResultResponse result
    ) {
        CoreIntentResponse intentResponse = intent == null
                ? null
                : new CoreIntentResponse(
                        intent.intent(),
                        intent.domain(),
                        intent.capability(),
                        intent.riskLevel(),
                        intent.requiresConfirmation(),
                        intent.confidence());
        return new CoreCommandResponse(
                command.getId(),
                command.getStatus(),
                new CoreInterpretationResponse(command.getInterpreterSource(), command.getInterpreterDetail()),
                intentResponse,
                result,
                command.getStatus() == CoreCommandStatus.NEEDS_CLARIFICATION
                        ? toClarificationResponse(readClarification(command.getClarificationJson()))
                        : null,
                command.getStatus() == CoreCommandStatus.NEEDS_CONFIRMATION
                        ? new CoreConfirmationResponse(
                                command.getConfirmationToken(),
                                "Esta acción necesita confirmación explícita antes de ejecutarse.")
                        : null,
                command.getOperatorMessage(),
                command.getSpeakableMessage());
    }

    private CoreCommandSummaryResponse toSummaryResponse(CoreCommandEntity command) {
        return new CoreCommandSummaryResponse(
                command.getId(),
                command.getStatus(),
                new CoreInterpretationResponse(command.getInterpreterSource(), command.getInterpreterDetail()),
                toIntentResponse(command),
                command.getRawInput(),
                command.getResultSummary(),
                command.getErrorCode(),
                command.getErrorMessage(),
                command.getOperatorMessage(),
                command.getSpeakableMessage(),
                command.getCreatedAt(),
                command.getFinishedAt());
    }

    private CoreCommandDetailResponse toDetailResponse(CoreCommandEntity command) {
        return new CoreCommandDetailResponse(
                command.getId(),
                command.getRawInput(),
                command.getChannel(),
                command.getStatus(),
                new CoreInterpretationResponse(command.getInterpreterSource(), command.getInterpreterDetail()),
                command.getDomain(),
                command.getIntent(),
                command.getCapability(),
                command.getRiskLevel(),
                command.isRequiresConfirmation(),
                command.isConfirmed(),
                command.getConfirmationToken(),
                command.getConfidence(),
                readJson(command.getRequestContextJson()),
                readJson(command.getParametersJson()),
                readJson(command.getInterpretedIntentJson()),
                readJson(command.getClarificationJson()),
                command.getResultType(),
                command.getTargetType(),
                command.getTargetId(),
                command.getResultSummary(),
                command.getErrorCode(),
                command.getErrorMessage(),
                command.getOperatorMessage(),
                command.getSpeakableMessage(),
                command.getCreatedAt(),
                command.getFinishedAt());
    }

    private CoreIntentResponse toIntentResponse(CoreCommandEntity command) {
        if (command.getDomain() == null && command.getCapability() == null) {
            return null;
        }
        return new CoreIntentResponse(
                command.getIntent(),
                command.getDomain(),
                command.getCapability(),
                command.getRiskLevel(),
                command.isRequiresConfirmation(),
                command.getConfidence());
    }

    private boolean matchesQuery(CoreCommandEntity command, String query) {
        if (query == null) {
            return true;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        return contains(command.getRawInput(), normalized)
                || contains(command.getCapability(), normalized)
                || contains(command.getIntent(), normalized)
                || contains(command.getResultSummary(), normalized)
                || contains(command.getErrorCode(), normalized)
                || contains(command.getErrorMessage(), normalized)
                || contains(command.getOperatorMessage(), normalized)
                || contains(command.getInterpreterDetail(), normalized);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse persisted Core command JSON", exception);
        }
    }

    private CoreRequestContext readRequestContext(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CoreRequestContext.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse persisted Core request context JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse persisted Core command parameters JSON", exception);
        }
    }

    private CoreIntentEnvelope restoreIntentEnvelope(CoreCommandEntity command) {
        if (command.getDomain() == null || command.getCapability() == null || command.getRiskLevel() == null) {
            throw new IllegalStateException("Persisted Core command is missing intent metadata");
        }
        return new CoreIntentEnvelope(
                command.getIntent(),
                command.getDomain(),
                command.getCapability(),
                readObjectMap(command.getParametersJson()),
                command.getConfidence(),
                command.getRiskLevel(),
                command.isRequiresConfirmation());
    }

    private CoreClarification readClarification(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CoreClarification.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse persisted Core clarification JSON", exception);
        }
    }

    private CoreClarificationResponse toClarificationResponse(CoreClarification clarification) {
        if (clarification == null) {
            return null;
        }
        return new CoreClarificationResponse(
                clarification.message(),
                clarification.options().stream()
                        .map(option -> new CoreClarificationOptionResponse(
                                option.type(),
                                option.targetId(),
                                option.label()))
                        .toList());
    }

    private boolean isConfirmed(CoreConfirmationRequest confirmation) {
        return confirmation != null && confirmation.confirmed();
    }

    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Core command payload", exception);
        }
    }
}
