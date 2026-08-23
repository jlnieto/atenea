package com.atenea.api.developmentchange;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.service.developmentchange.DevelopmentChangeService;
import com.atenea.service.developmentchange.DevelopmentChangeWorkspaceService;
import com.atenea.service.developmentchange.RemoteSessionService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/projects/{projectId}/development-changes")
public class DevelopmentChangeController {

    private final DevelopmentChangeService service;
    private final DevelopmentChangeWorkspaceService workspaceService;
    private final RemoteSessionService remoteSessionService;

    public DevelopmentChangeController(
            DevelopmentChangeService service,
            DevelopmentChangeWorkspaceService workspaceService,
            RemoteSessionService remoteSessionService) {
        this.service = service;
        this.workspaceService = workspaceService;
        this.remoteSessionService = remoteSessionService;
    }

    @GetMapping
    public List<DevelopmentChangeResponse> list(@PathVariable Long projectId) {
        return service.list(projectId);
    }

    @GetMapping("/{changeKey}")
    public DevelopmentChangeResponse detail(
            @PathVariable Long projectId,
            @PathVariable UUID changeKey) {
        return service.detail(projectId, changeKey);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DevelopmentChangeMutationResponse create(
            @AuthenticationPrincipal AuthenticatedOperator actor,
            @PathVariable Long projectId,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
            @RequestBody CreateDevelopmentChangeRequest request) {
        return service.create(actor, projectId, idempotencyKey, request);
    }

    @PostMapping("/{changeKey}/pause")
    public DevelopmentChangeMutationResponse pause(
            @AuthenticationPrincipal AuthenticatedOperator actor,
            @PathVariable Long projectId,
            @PathVariable UUID changeKey,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
        return service.pause(actor, projectId, changeKey, idempotencyKey);
    }

    @PostMapping("/{changeKey}/abandon")
    public DevelopmentChangeMutationResponse abandon(
            @AuthenticationPrincipal AuthenticatedOperator actor,
            @PathVariable Long projectId,
            @PathVariable UUID changeKey,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
        return service.abandon(actor, projectId, changeKey, idempotencyKey);
    }

    @PostMapping("/{changeKey}/sessions/{sessionId}/bind")
    public DevelopmentChangeMutationResponse bindSession(
            @AuthenticationPrincipal AuthenticatedOperator actor,
            @PathVariable Long projectId,
            @PathVariable UUID changeKey,
            @PathVariable Long sessionId,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
        return service.bindSession(actor, projectId, changeKey, sessionId, idempotencyKey);
    }

    @PostMapping("/{changeKey}/workspace/provision")
    public DevelopmentChangeWorkspaceOperationResponse provisionWorkspace(
            @AuthenticationPrincipal AuthenticatedOperator actor,
            @PathVariable Long projectId,
            @PathVariable UUID changeKey,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
        return workspaceService.provision(actor, projectId, changeKey, idempotencyKey);
    }

    @PostMapping("/{changeKey}/workspace/inspect")
    public DevelopmentChangeWorkspaceOperationResponse inspectWorkspace(
            @AuthenticationPrincipal AuthenticatedOperator actor,
            @PathVariable Long projectId,
            @PathVariable UUID changeKey,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey) {
        return workspaceService.inspect(actor, projectId, changeKey, idempotencyKey);
    }

    @PostMapping("/{changeKey}/workspace/reconcile")
    public DevelopmentChangeWorkspaceOperationResponse reconcileWorkspace(
            @AuthenticationPrincipal AuthenticatedOperator actor,
            @PathVariable Long projectId,
            @PathVariable UUID changeKey) {
        return workspaceService.reconcile(actor, projectId, changeKey);
    }

    @PostMapping("/{changeKey}/work-session:open-or-resolve")
    public RemoteSessionOperationResponse openOrResolveRemoteSession(
            @AuthenticationPrincipal AuthenticatedOperator actor,
            @PathVariable Long projectId,
            @PathVariable UUID changeKey,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
            @RequestBody(required = false) OpenOrResolveRemoteSessionRequest request) {
        return remoteSessionService.openOrResolve(
                actor, projectId, changeKey, idempotencyKey, request);
    }
}
