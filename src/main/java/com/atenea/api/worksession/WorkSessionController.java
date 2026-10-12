package com.atenea.api.worksession;

import com.atenea.service.worksession.WorkSessionService;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.atenea.service.worksession.ClosedValidationOperationService;
import com.atenea.service.worksession.RepositoryRoleSetService;
import com.atenea.persistence.worksession.ValidationOperationKind;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkSessionController {

    private final WorkSessionService workSessionService;
    private final WorkSessionGitHubService workSessionGitHubService;
    private final ClosedValidationOperationService closedValidationOperationService;
    private final RepositoryRoleSetService repositoryRoleSetService;

    public WorkSessionController(
            WorkSessionService workSessionService,
            WorkSessionGitHubService workSessionGitHubService,
            ClosedValidationOperationService closedValidationOperationService,
            RepositoryRoleSetService repositoryRoleSetService
    ) {
        this.workSessionService = workSessionService;
        this.workSessionGitHubService = workSessionGitHubService;
        this.closedValidationOperationService = closedValidationOperationService;
        this.repositoryRoleSetService = repositoryRoleSetService;
    }

    @GetMapping("/api/sessions/{sessionId}")
    public WorkSessionResponse getSession(@PathVariable Long sessionId) {
        return workSessionService.getSession(sessionId);
    }


    @PostMapping("/api/sessions/{sessionId}/validations/{operation}")
    public ValidationOperationResponse runValidation(
            @PathVariable Long sessionId,
            @PathVariable ValidationOperationKind operation
    ) {
        return closedValidationOperationService.run(sessionId, operation);
    }

    @PostMapping("/api/sessions/{sessionId}/validate-change")
    public DevelopmentChangeValidationResponse validateDevelopmentChange(
            @PathVariable Long sessionId
    ) {
        return closedValidationOperationService.advanceDevelopmentChange(sessionId);
    }

    @GetMapping("/api/sessions/{sessionId}/validation-evidence")
    public DevelopmentChangeValidationEvidenceResponse getDevelopmentChangeValidationEvidence(
            @PathVariable Long sessionId
    ) {
        return closedValidationOperationService.getDevelopmentChangeEvidence(sessionId);
    }

    @PostMapping("/api/sessions/{sessionId}/repository-role-sets/atenea-platform")
    public RepositoryRoleSetResponse ensureRepositoryRoles(@PathVariable Long sessionId) {
        return repositoryRoleSetService.ensure(sessionId);
    }

    @GetMapping("/api/sessions/{sessionId}/view")
    public WorkSessionViewResponse getSessionView(@PathVariable Long sessionId) {
        return workSessionService.getSessionView(sessionId);
    }

    @GetMapping("/api/sessions/{sessionId}/conversation-view")
    public WorkSessionConversationViewResponse getSessionConversationView(@PathVariable Long sessionId) {
        return workSessionService.getSessionConversationView(sessionId);
    }

    @PostMapping("/api/sessions/{sessionId}/close")
    public WorkSessionResponse closeSession(@PathVariable Long sessionId) {
        return workSessionService.closeSession(sessionId);
    }

    @PostMapping("/api/sessions/{sessionId}/close/conversation-view")
    public CloseWorkSessionConversationViewResponse closeSessionConversationView(@PathVariable Long sessionId) {
        return workSessionService.closeSessionConversationView(sessionId);
    }

    @PostMapping("/api/sessions/{sessionId}/publish")
    public WorkSessionResponse publishSession(
            @PathVariable Long sessionId,
            @Valid @RequestBody(required = false) PublishWorkSessionRequest request
    ) {
        return workSessionGitHubService.publishSession(sessionId, request);
    }

    @PostMapping("/api/sessions/{sessionId}/publish/conversation-view")
    public PublishWorkSessionConversationViewResponse publishSessionConversationView(
            @PathVariable Long sessionId,
            @Valid @RequestBody(required = false) PublishWorkSessionRequest request
    ) {
        return workSessionGitHubService.publishSessionConversationView(sessionId, request);
    }

    @PostMapping("/api/sessions/{sessionId}/pull-request/sync")
    public WorkSessionResponse syncPullRequest(@PathVariable Long sessionId) {
        return workSessionGitHubService.syncPullRequest(sessionId);
    }

    @PostMapping("/api/sessions/{sessionId}/pull-request/sync/conversation-view")
    public SyncWorkSessionPullRequestConversationViewResponse syncPullRequestConversationView(@PathVariable Long sessionId) {
        return workSessionGitHubService.syncPullRequestConversationView(sessionId);
    }
}
