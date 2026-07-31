package com.atenea.codexoperations;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.codexoperations.CodexSessionOperationsService.AdministratorInventoryResponse;
import com.atenea.codexoperations.CodexSessionOperationsService.CatalogResponse;
import com.atenea.codexoperations.CodexSessionOperationsService.PreferenceRequest;
import com.atenea.codexoperations.CodexSessionOperationsService.PreferenceResponse;
import com.atenea.codexoperations.CodexSessionOperationsService.ProfileRequest;
import com.atenea.codexoperations.CodexSessionOperationsService.ProgressReplayResponse;
import com.atenea.codexoperations.CodexSessionOperationsService.RecoveryRequest;
import com.atenea.codexoperations.CodexSessionOperationsService.RecoveryResponse;
import com.atenea.codexoperations.CodexSessionOperationsService.RunDetailResponse;
import com.atenea.codexoperations.CodexSessionOperationsService.SettingsResponse;
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
    private final ObjectMapper objectMapper;

    public CodexSessionOperationsController(
            CodexSessionOperationsService service,
            ObjectMapper objectMapper) {
        this.service = service;
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
        return service.administratorInventory(operator);
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
