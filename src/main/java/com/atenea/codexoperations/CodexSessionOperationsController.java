package com.atenea.codexoperations;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.codexoperations.CodexSessionOperationsService.CatalogResponse;
import com.atenea.codexoperations.CodexSessionOperationsService.PreferenceRequest;
import com.atenea.codexoperations.CodexSessionOperationsService.PreferenceResponse;
import com.atenea.codexoperations.CodexSessionOperationsService.ProfileRequest;
import com.atenea.codexoperations.CodexSessionOperationsService.ProgressReplayResponse;
import com.atenea.codexoperations.CodexSessionOperationsService.RecoveryRequest;
import com.atenea.codexoperations.CodexSessionOperationsService.RecoveryResponse;
import com.atenea.codexoperations.CodexSessionOperationsService.RunDetailResponse;
import com.atenea.codexoperations.CodexSessionOperationsService.SettingsResponse;
import com.atenea.codexoperations.ManagedCodexUpdateService.AdministratorInventoryResponse;
import com.atenea.codexoperations.ManagedCodexUpdateService.ActivationAuthorizationRequest;
import com.atenea.codexoperations.ManagedCodexUpdateService.ActivationAuthorizationResponse;
import com.atenea.codexoperations.ManagedCodexUpdateService.UpdatePlanRequest;
import com.atenea.codexoperations.ManagedCodexUpdateService.UpdatePlanResponse;
import com.atenea.codexoperations.ManagedCodexUpdateService.UpdateStageRequest;
import com.atenea.codexoperations.ManagedCodexUpdateService.UpdateStageResponse;
import com.atenea.codexoperations.ManagedCodexUpdateService.UpdateActivationRequest;
import com.atenea.codexoperations.ManagedCodexUpdateService.UpdateActivationResponse;
import com.atenea.codexoperations.ManagedCodexUpdateService.RollbackAuthorizationRequest;
import com.atenea.codexoperations.ManagedCodexUpdateService.RollbackAuthorizationResponse;
import com.atenea.codexoperations.ManagedCodexUpdateService.UpdateRollbackRequest;
import com.atenea.codexoperations.ManagedCodexUpdateService.UpdateRollbackResponse;
import com.atenea.codexoperations.ManagedCodexUpdateService.WorkerInventoryResponse;
import com.atenea.codexoperations.LegacyRemoteCloseService.LegacyRemoteCloseConfirmationRequest;
import com.atenea.codexoperations.LegacyRemoteCloseService.LegacyRemoteCloseOperationResponse;
import com.atenea.codexoperations.LegacyRemoteCloseService.LegacyRemoteClosePlanRequest;
import com.atenea.codexoperations.LegacyRemoteCloseService.LegacyRemoteClosePlanResponse;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CodexSessionOperationsController {

    private final CodexSessionOperationsService service;
    private final ManagedCodexUpdateService managedUpdateService;
    private final LegacyRemoteCloseService legacyRemoteCloseService;
    private final ObjectMapper objectMapper;

    public CodexSessionOperationsController(
            CodexSessionOperationsService service,
            ManagedCodexUpdateService managedUpdateService,
            LegacyRemoteCloseService legacyRemoteCloseService,
            ObjectMapper objectMapper) {
        this.service = service;
        this.managedUpdateService = managedUpdateService;
        this.legacyRemoteCloseService = legacyRemoteCloseService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/codex/catalog")
    public CatalogResponse catalog() { return service.catalog(); }

    @GetMapping("/api/projects/{projectId}/codex-settings")
    public SettingsResponse projectSettings(@PathVariable Long projectId) {
        return service.projectSettings(projectId);
    }

    @PutMapping("/api/projects/{projectId}/codex-settings")
    public SettingsResponse updateProjectSettings(
            @PathVariable Long projectId,
            @RequestBody JsonNode request) {
        return service.updateProjectSettings(projectId, closed(request, ProfileRequest.class,
                Set.of("modelId", "reasoningEffort", "catalogRevision", "idempotencyKey")));
    }

    @GetMapping("/api/sessions/{sessionId}/codex-settings")
    public SettingsResponse sessionSettings(@PathVariable Long sessionId) {
        return service.sessionSettings(sessionId);
    }

    @PutMapping("/api/sessions/{sessionId}/codex-settings")
    public SettingsResponse updateSessionSettings(
            @PathVariable Long sessionId,
            @RequestBody JsonNode request) {
        return service.updateSessionSettings(sessionId, closed(request, ProfileRequest.class,
                Set.of("modelId", "reasoningEffort", "catalogRevision", "idempotencyKey")));
    }

    @GetMapping("/api/runs/{runId}/codex-detail")
    public RunDetailResponse runDetail(@PathVariable Long runId) {
        return service.runDetail(runId);
    }

    @GetMapping("/api/runs/{runId}/progress")
    public ProgressReplayResponse progress(
            @PathVariable Long runId,
            @RequestParam(defaultValue = "0") long afterSequence) {
        return service.progress(runId, afterSequence);
    }

    @PostMapping("/api/runs/{runId}/recovery")
    public RecoveryResponse recovery(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable Long runId,
            @RequestBody JsonNode request) {
        return service.recovery(operator, runId, closed(request, RecoveryRequest.class,
                Set.of("workSessionId", "action", "idempotencyKey")));
    }

    @GetMapping("/api/mobile/notifications/devices/{deviceId}/preferences")
    public List<PreferenceResponse> preferences(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable Long deviceId) {
        return service.preferences(operator, deviceId);
    }

    @PutMapping("/api/mobile/notifications/devices/{deviceId}/preferences")
    public PreferenceResponse updatePreference(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable Long deviceId,
            @RequestBody JsonNode request) {
        return service.updatePreference(operator, deviceId, closed(request, PreferenceRequest.class,
                Set.of("category", "enabled")));
    }

    @GetMapping("/api/admin/codex/inventory")
    public AdministratorInventoryResponse administratorInventory(
            @AuthenticationPrincipal AuthenticatedOperator operator) {
        return managedUpdateService.administratorInventory(operator);
    }

    @GetMapping("/api/codex/workers/{workerId}/inventory")
    public WorkerInventoryResponse workerInventory(@PathVariable String workerId) {
        return managedUpdateService.workerInventory(workerId);
    }

    @PostMapping("/api/admin/codex/update-plans")
    public UpdatePlanResponse createUpdatePlan(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestBody JsonNode request) {
        return managedUpdateService.createUpdatePlan(operator, closed(request, UpdatePlanRequest.class,
                Set.of("operation", "workerId", "idempotencyKey")));
    }

    @GetMapping("/api/admin/codex/update-plans/{planId}")
    public UpdatePlanResponse updatePlan(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable java.util.UUID planId) {
        return managedUpdateService.updatePlan(operator, planId);
    }

    @PostMapping("/api/admin/codex/update-stages")
    public UpdateStageResponse stageUpdate(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestBody JsonNode request) {
        return managedUpdateService.stageUpdate(operator,
                closed(request, UpdateStageRequest.class,
                        Set.of("operation", "planId", "candidateId", "idempotencyKey")));
    }

    @GetMapping("/api/admin/codex/update-stages/{stageId}")
    public UpdateStageResponse updateStage(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable java.util.UUID stageId) {
        return managedUpdateService.updateStage(operator, stageId);
    }

    @PostMapping("/api/admin/codex/update-activation-authorizations")
    public ActivationAuthorizationResponse authorizeActivation(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestBody JsonNode request) {
        return managedUpdateService.authorizeActivation(operator,
                closed(request, ActivationAuthorizationRequest.class,
                        Set.of("operation", "planId", "candidateId", "idempotencyKey")));
    }

    @GetMapping("/api/admin/codex/update-activation-authorizations/{authorizationId}")
    public ActivationAuthorizationResponse activationAuthorization(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable java.util.UUID authorizationId) {
        return managedUpdateService.activationAuthorization(operator, authorizationId);
    }

    @PostMapping("/api/admin/codex/update-activations")
    public UpdateActivationResponse activateUpdate(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestBody JsonNode request) {
        return managedUpdateService.activateUpdate(operator,
                closed(request, UpdateActivationRequest.class,
                        Set.of("operation", "planId", "candidateId", "authorizationId",
                                "idempotencyKey")));
    }

    @GetMapping("/api/admin/codex/update-activations/{activationId}")
    public UpdateActivationResponse updateActivation(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable java.util.UUID activationId) {
        return managedUpdateService.updateActivation(operator, activationId);
    }

    @PostMapping("/api/admin/codex/update-rollback-authorizations")
    public RollbackAuthorizationResponse authorizeRollback(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestBody JsonNode request) {
        return managedUpdateService.authorizeRollback(operator,
                closed(request, RollbackAuthorizationRequest.class,
                        Set.of("operation", "activationId", "idempotencyKey")));
    }

    @GetMapping("/api/admin/codex/update-rollback-authorizations/{authorizationId}")
    public RollbackAuthorizationResponse rollbackAuthorization(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable java.util.UUID authorizationId) {
        return managedUpdateService.rollbackAuthorization(operator, authorizationId);
    }

    @PostMapping("/api/admin/codex/update-rollbacks")
    public UpdateRollbackResponse rollbackUpdate(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestBody JsonNode request) {
        return managedUpdateService.rollbackUpdate(operator,
                closed(request, UpdateRollbackRequest.class,
                        Set.of("operation", "activationId", "authorizationId",
                                "idempotencyKey")));
    }

    @GetMapping("/api/admin/codex/update-rollbacks/{rollbackId}")
    public UpdateRollbackResponse updateRollback(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable java.util.UUID rollbackId) {
        return managedUpdateService.updateRollback(operator, rollbackId);
    }

    @PostMapping("/api/admin/work-sessions/{sessionId}/remote-close-plans")
    public LegacyRemoteClosePlanResponse createLegacyRemoteClosePlan(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable Long sessionId,
            @RequestBody JsonNode request) {
        return legacyRemoteCloseService.createPlan(operator, sessionId,
                closed(request, LegacyRemoteClosePlanRequest.class,
                        Set.of("operation", "idempotencyKey")));
    }

    @GetMapping("/api/admin/work-sessions/remote-close-plans/{planId}")
    public LegacyRemoteClosePlanResponse legacyRemoteClosePlan(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable java.util.UUID planId) {
        return legacyRemoteCloseService.plan(operator, planId);
    }

    @PostMapping("/api/admin/work-sessions/{sessionId}/remote-close-reconciliations")
    public LegacyRemoteCloseOperationResponse confirmLegacyRemoteClose(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable Long sessionId,
            @RequestBody JsonNode request) {
        return legacyRemoteCloseService.confirm(operator, sessionId,
                closed(request, LegacyRemoteCloseConfirmationRequest.class,
                        Set.of("operation", "planId", "ownershipFingerprintSha256",
                                "idempotencyKey")));
    }

    @GetMapping("/api/admin/work-sessions/remote-close-reconciliations/{operationId}")
    public LegacyRemoteCloseOperationResponse legacyRemoteCloseOperation(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable java.util.UUID operationId) {
        return legacyRemoteCloseService.operation(operator, operationId);
    }

    private <T> T closed(JsonNode node, Class<T> type, Set<String> exactFields) {
        if (node == null || !node.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON object required");
        }
        Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(exactFields)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request fields do not match the closed contract");
        }
        try {
            return objectMapper.treeToValue(node, type);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid closed request");
        }
    }
}
