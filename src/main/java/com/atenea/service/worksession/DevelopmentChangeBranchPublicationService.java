package com.atenea.service.worksession;

import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionAcceptanceState;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.DevelopmentChangeBranchPublication;
import com.atenea.remoteworker.DevelopmentChangeBranchPublicationCommand;
import com.atenea.remoteworker.DevelopmentChangeBranchPublicationGateway;
import com.atenea.remoteworker.ProjectCodexIdentity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DevelopmentChangeBranchPublicationService {

    public static final String GITHUB_REPOSITORY = "jlnieto/atenea";

    private final WorkSessionRepository sessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final DevelopmentChangeBranchPublicationGateway gateway;
    private final TransactionTemplate transaction;
    private com.atenea.delivery.SourceFinalizationService finalizations;

    @org.springframework.beans.factory.annotation.Autowired
    void setFinalizations(com.atenea.delivery.SourceFinalizationService finalizations) {
        this.finalizations = finalizations;
    }

    public DevelopmentChangeBranchPublicationService(
            WorkSessionRepository sessionRepository,
            AgentRunRepository agentRunRepository,
            DevelopmentChangeBranchPublicationGateway gateway,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.gateway = gateway;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public PublishedIdentity publish(Long sessionId) {
        DevelopmentChangeBranchPublicationCommand command = Objects.requireNonNull(
                transaction.execute(ignored -> commandFor(sessionId)));
        if (finalizations != null) {
            var recovered = finalizations.publish(sessionId, command);
            if (recovered.isPresent()) return recovered.get();
        }
        DevelopmentChangeBranchPublication result = gateway.publish(command);
        if (result == null) {
            throw conflict(sessionId, "platform returned no publication identity");
        }
        return Objects.requireNonNull(transaction.execute(ignored ->
                persistExact(sessionId, command, result)));
    }

    private DevelopmentChangeBranchPublicationCommand commandFor(Long sessionId) {
        WorkSessionEntity session = sessionRepository
                .findLockedWithProjectAndDevelopmentChangeById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        DevelopmentChangeEntity change = requireExactOwner(session);
        String stable = String.join("|",
                "change-branch-publication-v1",
                change.getChangeKey().toString(),
                Long.toString(change.getSourceRevision()),
                change.getObservedCanonicalCommit(),
                change.getSourceState() == DevelopmentChangeSourceState.DIRTY
                        ? change.getSourceFingerprintSha256()
                        : "clean");
        UUID idempotencyKey = UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8));
        UUID operationId = UUID.nameUUIDFromBytes(
                ("operation|" + stable).getBytes(StandardCharsets.UTF_8));
        return new DevelopmentChangeBranchPublicationCommand(
                operationId,
                idempotencyKey,
                change.getChangeKey(),
                change.getProject().getId(),
                ProjectCodexIdentity.PROJECT_IDENTITY,
                ProjectCodexIdentity.REPOSITORY,
                ProjectCodexIdentity.BRANCH,
                change.getBaseCommit(),
                change.getObservedCanonicalCommit(),
                change.getWorkspaceBranch(),
                change.getWorkspaceIdentity(),
                change.getSelectedWorkerId(),
                change.getSourceRevision(),
                change.getSourceState() == DevelopmentChangeSourceState.DIRTY
                        ? change.getSourceFingerprintSha256()
                        : null);
    }

    private PublishedIdentity persistExact(
            Long sessionId,
            DevelopmentChangeBranchPublicationCommand command,
            DevelopmentChangeBranchPublication result) {
        WorkSessionEntity session = sessionRepository
                .findLockedWithProjectAndDevelopmentChangeById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        DevelopmentChangeEntity change = requireExactOwner(session);
        if (agentRunRepository.existsBySessionIdAndStatusIn(
                    sessionId, AgentRunStatus.nonTerminalStatuses())
                || !matches(command, change)) {
            throw conflict(sessionId, "durable DevelopmentChange identity changed during publication");
        }
        if (!result.publishedHeadSha().matches("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")
                || !result.publicationReceiptSha256().matches("^[0-9a-f]{64}$")) {
            throw conflict(sessionId, "platform publication identity is invalid");
        }
        if (session.getPublishedChangeKey() != null) {
            requirePersistedReplay(session, command, result);
        } else {
            session.setWorkspaceBranch(command.workspaceBranch());
            session.setFinalCommitSha(result.publishedHeadSha());
            session.setPublishedChangeKey(command.changeKey());
            session.setPublishedSourceRevision(command.sourceRevision());
            session.setPublishedSourceFingerprintSha256(change.getSourceFingerprintSha256());
            session.setPublishedWorkspaceOwnershipFingerprintSha256(
                    change.getSourceFingerprintSha256());
            session.setPublishedRepository(GITHUB_REPOSITORY);
            session.setPublishedBaseBranch(command.repositoryBranch());
            session.setPublishedHeadBranch(command.workspaceBranch());
            session.setPublicationReceiptSha256(result.publicationReceiptSha256());
            session.setUpdatedAt(Instant.now());
            sessionRepository.saveAndFlush(session);
        }
        return new PublishedIdentity(
                GITHUB_REPOSITORY,
                command.repositoryBranch(),
                command.workspaceBranch(),
                result.publishedHeadSha(),
                command.changeKey(),
                command.sourceRevision(),
                command.sourceFingerprintSha256(),
                result.publicationReceiptSha256());
    }

    public DevelopmentChangeEntity requireExactOwner(WorkSessionEntity session) {
        return requireExactOwner(session, false);
    }

    /** Read/continue delivery after integration; never authority to republish a branch. */
    public DevelopmentChangeEntity requireExactIntegratedOwner(WorkSessionEntity session) {
        if (session.getAcceptanceState() != WorkSessionAcceptanceState.INTEGRATION_READY
                || session.getPullRequestStatus() != WorkSessionPullRequestStatus.MERGED
                || session.getIntegrationReadyAt() == null
                || session.getValidatedAt() == null
                || session.getSourceTreeObservedAt() == null
                || !sha256(session.getValidationProjectionSha256())
                || session.getValidationDefinitionRevision() == null
                || session.getValidationDefinitionRevision().isBlank()) {
            throw conflict(session.getId(), "exact integrated acceptance projection is required");
        }
        return requireExactOwner(session, true);
    }

    private DevelopmentChangeEntity requireExactOwner(WorkSessionEntity session, boolean integrated) {
        DevelopmentChangeEntity change = session.getDevelopmentChange();
        String expectedBranch = change == null || change.getChangeKey() == null
                ? null : "atenea/change-" + change.getChangeKey();
        String expectedWorkspace = change == null || change.getChangeKey() == null
                ? null : "remote:" + ProjectCodexIdentity.WORKER_ID
                    + ":change:" + change.getChangeKey();
        if (sessionRepository.existsActiveSourceUpdateBySessionId(session.getId())
                || change == null
                || change.getProject() == null
                || session.getProject() == null
                || !Objects.equals(change.getProject().getId(), session.getProject().getId())
                || change.getStatus() != DevelopmentChangeStatus.OPEN
                || change.getWorkspaceState() != DevelopmentChangeWorkspaceState.READY
                || change.getValidationState() != DevelopmentChangeProjectionState.CURRENT
                || session.getAcceptanceState() != (integrated
                        ? WorkSessionAcceptanceState.INTEGRATION_READY : WorkSessionAcceptanceState.VALIDATED)
                || !Objects.equals(
                        session.getSourceTreeFingerprintSha256(),
                        change.getSourceFingerprintSha256())
                || change.getSourceState() == DevelopmentChangeSourceState.STALE
                || change.getSourceState() == DevelopmentChangeSourceState.BLOCKED
                || session.getStatus() != WorkSessionStatus.OPEN
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.matches(session)
                || !ProjectCodexIdentity.matches(change.getProject())
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                || !ProjectCodexIdentity.WORKER_ID.equals(change.getSelectedWorkerId())
                || !Objects.equals(change.getSelectedWorkerId(), session.getSelectedWorkerId())
                || !Objects.equals(change.getWorkspaceBranch(), expectedBranch)
                || !Objects.equals(session.getWorkspaceBranch(), expectedBranch)
                || !Objects.equals(change.getWorkspaceIdentity(), expectedWorkspace)
                || !Objects.equals(session.getWorkspaceIdentity(), expectedWorkspace)
                || !Objects.equals(change.getBaseRef(), "refs/heads/" + ProjectCodexIdentity.BRANCH)
                || !Objects.equals(session.getBaseBranch(), ProjectCodexIdentity.BRANCH)
                || !gitCommit(change.getBaseCommit())
                || !gitCommit(change.getObservedCanonicalCommit())
                || change.getSourceRevision() < 0
                || (change.getSourceState() == DevelopmentChangeSourceState.DIRTY
                    && !sha256(change.getSourceFingerprintSha256()))) {
            throw conflict(session.getId(),
                    "DevelopmentChange publication ownership is incomplete, stale, or foreign");
        }
        return change;
    }

    private boolean matches(
            DevelopmentChangeBranchPublicationCommand command,
            DevelopmentChangeEntity change) {
        return Objects.equals(command.changeKey(), change.getChangeKey())
                && command.databaseProjectId() == change.getProject().getId()
                && Objects.equals(command.baseCommit(), change.getBaseCommit())
                && Objects.equals(command.sourceCommit(), change.getObservedCanonicalCommit())
                && Objects.equals(command.workspaceBranch(), change.getWorkspaceBranch())
                && Objects.equals(command.workspaceIdentity(), change.getWorkspaceIdentity())
                && Objects.equals(command.workerId(), change.getSelectedWorkerId())
                && command.sourceRevision() == change.getSourceRevision()
                && Objects.equals(command.sourceFingerprintSha256(),
                        change.getSourceState() == DevelopmentChangeSourceState.DIRTY
                                ? change.getSourceFingerprintSha256()
                                : null);
    }

    private void requirePersistedReplay(
            WorkSessionEntity session,
            DevelopmentChangeBranchPublicationCommand command,
            DevelopmentChangeBranchPublication result) {
        if (!Objects.equals(session.getPublishedChangeKey(), command.changeKey())
                || !Objects.equals(session.getPublishedSourceRevision(), command.sourceRevision())
                || !Objects.equals(session.getPublishedRepository(), GITHUB_REPOSITORY)
                || !Objects.equals(session.getPublishedBaseBranch(), command.repositoryBranch())
                || !Objects.equals(session.getPublishedHeadBranch(), command.workspaceBranch())
                || !Objects.equals(session.getWorkspaceBranch(), command.workspaceBranch())
                || !Objects.equals(session.getFinalCommitSha(), result.publishedHeadSha())
                || !Objects.equals(session.getPublicationReceiptSha256(),
                        result.publicationReceiptSha256())) {
            throw conflict(session.getId(), "persisted publication identity does not match replay");
        }
    }

    private static boolean gitCommit(String value) {
        return value != null && value.matches("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    private static WorkSessionPublishConflictException conflict(Long sessionId, String reason) {
        return new WorkSessionPublishConflictException(sessionId, reason);
    }

    public record PublishedIdentity(
            String repository,
            String baseBranch,
            String headBranch,
            String headSha,
            UUID changeKey,
            long sourceRevision,
            String sourceFingerprintSha256,
            String publicationReceiptSha256) {
    }
}
