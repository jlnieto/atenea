package com.atenea.api;

import com.atenea.attachments.AttachmentFeatureDisabledException;
import com.atenea.attachments.AttachmentWorkerException;
import com.atenea.previews.PreviewFeatureDisabledException;
import com.atenea.previews.PreviewWorkerException;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.webauthn.WebAuthnCredentialNotAcceptedException;
import com.atenea.auth.webauthn.WebAuthnLifecycleUnavailableException;
import com.atenea.auth.webauthn.WebAuthnSnapshotIncompleteException;
import com.atenea.service.developmentchange.DevelopmentChangeNotFoundException;
import com.atenea.service.developmentchange.DevelopmentChangeRejectedException;
import com.atenea.service.developmentchange.RemoteSessionRejectedException;
import com.atenea.service.project.CanonicalProjectConflictException;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.service.core.CoreCommandNotFoundException;
import com.atenea.service.core.CoreCommandRejectedException;
import com.atenea.service.core.CoreVoiceTranscriptionException;
import com.atenea.service.core.CoreVoiceUnavailableException;
import com.atenea.service.core.CoreSpeechSynthesisException;
import com.atenea.service.mobile.MobileUploadException;
import com.atenea.service.project.DuplicateProjectNameException;
import com.atenea.service.project.ProjectRepoPathMissingGitDirectoryException;
import com.atenea.service.project.ProjectRepoPathNotDirectoryException;
import com.atenea.service.project.ProjectRepoPathNotFoundException;
import com.atenea.service.project.ProjectRepoPathOutsideWorkspaceException;
import com.atenea.service.rescue.RescueSessionAlreadyRunningException;
import com.atenea.service.rescue.RescueSessionClosedException;
import com.atenea.service.rescue.RescueSessionExecutionFailedException;
import com.atenea.service.rescue.RescueSessionNotFoundException;
import com.atenea.service.operations.OperationsHostNotFoundException;
import com.atenea.service.operations.OperationsIncidentNotFoundException;
import com.atenea.service.operations.OperationsRemoteExecutionException;
import com.atenea.service.operations.OperationsServiceNotFoundException;
import com.atenea.service.git.GitRepositoryOperationException;
import com.atenea.service.worksession.AgentRunAlreadyRunningException;
import com.atenea.service.worksession.AgentRunNotFoundException;
import com.atenea.service.worksession.AgentRunTransitionNotAllowedException;
import com.atenea.service.worksession.AgentRunRecoveryAuthorizationException;
import com.atenea.service.worksession.AgentRunRecoveryConflictException;
import com.atenea.service.worksession.AttachmentConflictException;
import com.atenea.service.worksession.AttachmentLimitException;
import com.atenea.service.worksession.AttachmentNotFoundException;
import com.atenea.service.worksession.AttachmentOwnershipException;
import com.atenea.service.worksession.ApprovedPriceEstimateNotFoundException;
import com.atenea.service.worksession.OpenWorkSessionAlreadyExistsException;
import com.atenea.service.worksession.PreviewConflictException;
import com.atenea.service.worksession.PreviewNotFoundException;
import com.atenea.service.worksession.PreviewOwnershipException;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DevelopmentChangeNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleDevelopmentChangeNotFound(
            DevelopmentChangeNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(DevelopmentChangeRejectedException.class)
    public ResponseEntity<com.atenea.api.developmentchange.DevelopmentChangeFailureResponse>
            handleDevelopmentChangeRejected(DevelopmentChangeRejectedException exception) {
        return ResponseEntity.status(exception.response().status()).body(exception.response());
    }

    @ExceptionHandler(RemoteSessionRejectedException.class)
    public ResponseEntity<com.atenea.api.developmentchange.RemoteSessionFailureResponse>
            handleRemoteSessionRejected(RemoteSessionRejectedException exception) {
        return ResponseEntity.status(exception.response().status()).body(exception.response());
    }

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

    @ExceptionHandler(AttachmentNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAttachmentNotFound(AttachmentNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler({
            AttachmentConflictException.class,
            AttachmentOwnershipException.class,
            AttachmentFeatureDisabledException.class
    })
    public ResponseEntity<ApiErrorResponse> handleAttachmentConflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler({AttachmentLimitException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiErrorResponse> handleAttachmentLimit(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiErrorResponse(
                        exception instanceof AttachmentLimitException
                                ? exception.getMessage()
                                : "El adjunto supera el límite permitido.",
                        List.of()));
    }

    @ExceptionHandler(AttachmentWorkerException.class)
    public ResponseEntity<ApiErrorResponse> handleAttachmentWorker(AttachmentWorkerException exception) {
        int upstream = exception.getStatusCode();
        HttpStatus status = switch (upstream) {
            case 409 -> HttpStatus.CONFLICT;
            case 413 -> HttpStatus.PAYLOAD_TOO_LARGE;
            case 415 -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(exception.getMessage(), List.of(exception.getCode())));
    }

    @ExceptionHandler(PreviewNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handlePreviewNotFound(PreviewNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler({
            PreviewConflictException.class,
            PreviewOwnershipException.class,
            PreviewFeatureDisabledException.class
    })
    public ResponseEntity<ApiErrorResponse> handlePreviewConflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(PreviewWorkerException.class)
    public ResponseEntity<ApiErrorResponse> handlePreviewWorker(PreviewWorkerException exception) {
        HttpStatus status = switch (exception.getStatusCode()) {
            case 401 -> HttpStatus.BAD_GATEWAY;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(exception.getMessage(), List.of(exception.getCode())));
    }

    @ExceptionHandler(RescueSessionNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleRescueSessionNotFound(RescueSessionNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(CoreCommandNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleCoreCommandNotFound(CoreCommandNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler({
            OperationsHostNotFoundException.class,
            OperationsIncidentNotFoundException.class,
            OperationsServiceNotFoundException.class
    })
    public ResponseEntity<ApiErrorResponse> handleOperationsNotFound(RuntimeException exception) {
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
            RescueSessionAlreadyRunningException.class,
            RescueSessionClosedException.class,
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

    @ExceptionHandler(RescueSessionExecutionFailedException.class)
    public ResponseEntity<ApiErrorResponse> handleRescueSessionExecutionFailed(
            RescueSessionExecutionFailedException exception
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

    @ExceptionHandler(AgentRunRecoveryAuthorizationException.class)
    public ResponseEntity<ApiErrorResponse> handleRecoveryAuthorization(
            AgentRunRecoveryAuthorizationException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(AgentRunRecoveryConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleRecoveryConflict(
            AgentRunRecoveryConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(MobileUploadException.class)
    public ResponseEntity<ApiErrorResponse> handleMobileUpload(MobileUploadException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(WebAuthnCredentialNotAcceptedException.class)
    public ResponseEntity<ApiErrorResponse> handleWebAuthnCredentialNotAccepted(
            WebAuthnCredentialNotAcceptedException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiErrorResponse(
                        exception.getMessage(),
                        List.of(),
                        null,
                        null,
                        "SIGNAL_UNKNOWN_CREDENTIAL",
                        false));
    }

    @ExceptionHandler({
            WebAuthnLifecycleUnavailableException.class,
            WebAuthnSnapshotIncompleteException.class
    })
    public ResponseEntity<ApiErrorResponse> handleWebAuthnLifecycleConflict(
            RuntimeException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        exception.getMessage(),
                        List.of(),
                        "ACTION_REQUIRED",
                        null,
                        "REVIEW_PASSKEY_INVENTORY",
                        false));
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

    @ExceptionHandler(OperationsRemoteExecutionException.class)
    public ResponseEntity<ApiErrorResponse> handleOperationsRemoteExecution(
            OperationsRemoteExecutionException exception
    ) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(CoreSpeechSynthesisException.class)
    public ResponseEntity<ApiErrorResponse> handleCoreSpeechSynthesis(
            CoreSpeechSynthesisException exception
    ) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorResponse(exception.getMessage(), List.of()));
    }

    private static String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
