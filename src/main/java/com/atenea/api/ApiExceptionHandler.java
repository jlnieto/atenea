package com.atenea.api;

import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.service.project.CanonicalProjectConflictException;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.service.core.CoreCommandNotFoundException;
import com.atenea.service.core.CoreCommandRejectedException;
import com.atenea.service.core.CoreVoiceTranscriptionException;
import com.atenea.service.core.CoreVoiceUnavailableException;
import com.atenea.service.project.DuplicateProjectNameException;
import com.atenea.service.project.ProjectRepoPathMissingGitDirectoryException;
import com.atenea.service.project.ProjectRepoPathNotDirectoryException;
import com.atenea.service.project.ProjectRepoPathNotFoundException;
import com.atenea.service.project.ProjectRepoPathOutsideWorkspaceException;
import com.atenea.service.git.GitRepositoryOperationException;
import com.atenea.service.worksession.AgentRunAlreadyRunningException;
import com.atenea.service.worksession.AgentRunNotFoundException;
import com.atenea.service.worksession.AgentRunTransitionNotAllowedException;
import com.atenea.service.worksession.ApprovedPriceEstimateNotFoundException;
import com.atenea.service.worksession.OpenWorkSessionAlreadyExistsException;
import com.atenea.service.worksession.WorkSessionNotOpenException;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import com.atenea.service.worksession.WorkSessionOperationBlockedException;
import com.atenea.service.worksession.WorkSessionProjectNotFoundException;
import com.atenea.service.worksession.WorkSessionAlreadyRunningException;
import com.atenea.service.worksession.WorkSessionCloseBlockedException;
import com.atenea.service.worksession.SessionDeliverableNotFoundException;
import com.atenea.service.worksession.WorkSessionPublishConflictException;
import com.atenea.service.worksession.WorkSessionTurnExecutionFailedException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DuplicateProjectNameException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateProjectName(DuplicateProjectNameException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(CanonicalProjectConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleCanonicalProjectConflict(CanonicalProjectConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler({
            ProjectRepoPathOutsideWorkspaceException.class,
            ProjectRepoPathNotFoundException.class,
            ProjectRepoPathNotDirectoryException.class,
            ProjectRepoPathMissingGitDirectoryException.class
    })
    public ResponseEntity<ApiErrorResponse> handleProjectPathValidation(RuntimeException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(WorkSessionProjectNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkSessionProjectNotFound(
            WorkSessionProjectNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(WorkSessionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkSessionNotFound(WorkSessionNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(CoreCommandNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCoreCommandNotFound(CoreCommandNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(AgentRunNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAgentRunNotFound(AgentRunNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(SessionDeliverableNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleSessionDeliverableNotFound(
            SessionDeliverableNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(ApprovedPriceEstimateNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleApprovedPriceEstimateNotFound(
            ApprovedPriceEstimateNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(OpenWorkSessionAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleOpenWorkSessionAlreadyExists(
            OpenWorkSessionAlreadyExistsException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(WorkSessionOperationBlockedException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkSessionOperationBlocked(
            WorkSessionOperationBlockedException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(WorkSessionCloseBlockedException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkSessionCloseBlocked(
            WorkSessionCloseBlockedException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        exception.getMessage(),
                        exception.getDetails(),
                        exception.getState(),
                        exception.getReason(),
                        exception.getAction(),
                        exception.isRetryable()));
    }

    @ExceptionHandler({
            WorkSessionNotOpenException.class,
            WorkSessionAlreadyRunningException.class,
            AgentRunAlreadyRunningException.class,
            AgentRunTransitionNotAllowedException.class,
            WorkSessionPublishConflictException.class
    })
    public ResponseEntity<ApiErrorResponse> handleAgentRunConflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(WorkSessionTurnExecutionFailedException.class)
    public ResponseEntity<ApiErrorResponse> handleWorkSessionTurnExecutionFailed(
            WorkSessionTurnExecutionFailedException exception
    ) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(GitHubIntegrationException.class)
    public ResponseEntity<ApiErrorResponse> handleGitHubIntegration(GitHubIntegrationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(GitRepositoryOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleTaskLaunchBlocked(GitRepositoryOperationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(ApiExceptionHandler::formatFieldError)
                .toList();

        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse("Validation failed", details));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(OperatorAuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleOperatorAuthentication(
            OperatorAuthenticationException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(CoreCommandRejectedException.class)
    public ResponseEntity<ApiErrorResponse> handleCoreCommandRejected(
            CoreCommandRejectedException exception
    ) {
        return ResponseEntity.status(exception.getHttpStatus())
                .body(new ApiErrorResponse(exception.getMessage(), List.of(exception.getCode())));
    }

    @ExceptionHandler(CoreVoiceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleCoreVoiceUnavailable(
            CoreVoiceUnavailableException exception
    ) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(CoreVoiceTranscriptionException.class)
    public ResponseEntity<ApiErrorResponse> handleCoreVoiceTranscription(
            CoreVoiceTranscriptionException exception
    ) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    private static String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
