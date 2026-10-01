package com.atenea.delivery;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.AuthenticatedSession;
import com.atenea.auth.action.PrivilegedActionAuthorizationGrant;
import com.atenea.auth.action.PrivilegedActionAuthorizationService;
import com.atenea.auth.action.PrivilegedActionBinding;
import com.atenea.auth.recovery.OperatorRecoveryService;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.DevelopmentChangeBranchPublicationService;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MobileDeliveryService {
    private static final GitHubRepositoryRef APP = new GitHubRepositoryRef("jlnieto", "atenea");
    private static final GitHubRepositoryRef PLATFORM = new GitHubRepositoryRef("jlnieto", "atenea-remote-worker-spec");
    private final DeliveryStore store;
    private final WorkSessionRepository sessions;
    private final AgentRunRepository runs;
    private final OperatorRepository operators;
    private final DevelopmentChangeBranchPublicationService ownership;
    private final WorkSessionGitHubService publication;
    private final GitHubClient github;
    private final ReleaseControlClient executor;
    private final OperatorRecoveryService factors;
    private final PrivilegedActionAuthorizationService grants;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public MobileDeliveryService(DeliveryStore store, WorkSessionRepository sessions, AgentRunRepository runs,
            OperatorRepository operators, DevelopmentChangeBranchPublicationService ownership,
            WorkSessionGitHubService publication, GitHubClient github, ReleaseControlClient executor,
            OperatorRecoveryService factors, PrivilegedActionAuthorizationService grants,
            ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.store = store; this.sessions = sessions; this.runs = runs; this.operators = operators;
        this.ownership = ownership; this.publication = publication; this.github = github;
        this.executor = executor; this.factors = factors; this.grants = grants; this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<DeliveryOperation.DeliveryView> list(Long sessionId, AuthenticatedOperator actor) {
        administrator(actor.operatorId());
        session(sessionId, false);
        return store.list(sessionId).stream().map(DeliveryOperation::view).toList();
    }
    public boolean isEnabled() { return executor.enabled(); }

    public DeliveryOperation.DeliveryView request(Long sessionId, AuthenticatedOperator actor, String kind, DeliveryTarget target) {
        enabled();
        administrator(actor.operatorId());
        return Objects.requireNonNull(transaction.execute(ignored -> {
            WorkSessionEntity session = session(sessionId, true);
            owned(session);
            idle(sessionId);
            for (DeliveryOperation operation : store.list(sessionId)) {
                if (operation.kind().equals(kind) && operation.target() == target && !operation.terminal()) return operation.view();
            }
            if (!"RELEASE".equals(kind)) {
                if (target != DeliveryTarget.APP_PROD || !List.of("PUBLISH_PR", "INTEGRATE").contains(kind)) {
                    throw new DeliveryRejectedException("CLOSED_REQUEST_REQUIRED");
                }
                if ("PUBLISH_PR".equals(kind)) {
                    if (session.getPullRequestStatus() != null && session.getPullRequestStatus() != WorkSessionPullRequestStatus.NOT_CREATED) {
                        var receipt = store.list(sessionId).stream().filter(op -> op.kind().equals(kind) && op.state().equals("SUCCEEDED")).findFirst();
                        if (receipt.isPresent()) return receipt.get().view();
                        published(session);
                        if (session.getPullRequestStatus() == WorkSessionPullRequestStatus.DECLINED) {
                            throw new DeliveryRejectedException("PR_CLOSED");
                        }
                        // Adopt only the already-durable owned publication. No second
                        // push, PR, prompt or AgentRun is created for an existing ticket.
                        return store.create(sessionId, actor.operatorId(), kind, target, session.getFinalCommitSha(),
                                "SUCCEEDED", mapper.createObjectNode().put("pullRequestUrl",session.getPullRequestUrl())
                                    .put("headCommit",session.getFinalCommitSha()).put("origin","EXISTING_PUBLICATION")).view();
                    }
                } else {
                    published(session);
                    var completed = store.list(sessionId).stream().filter(op -> op.kind().equals(kind) && op.state().equals("SUCCEEDED")
                            && Objects.equals(op.sourceCommit(), session.getFinalCommitSha())).findFirst();
                    if (completed.isPresent()) return completed.get().view();
                }
                return store.create(sessionId, actor.operatorId(), kind, target,
                        "INTEGRATE".equals(kind) ? session.getFinalCommitSha() : null,
                        "QUEUED", mapper.createObjectNode()).view();
            }
            store.lockReleaseIntent();
            if (store.activeRelease().isPresent()) throw new DeliveryRejectedException("RELEASE_IN_PROGRESS");
            String source;
            if (target == DeliveryTarget.AX42_PLATFORM) {
                // A fixed reviewed repository, not a caller-supplied repo/SHA/ref.
                source = github.canonicalMain(PLATFORM);
            } else {
                published(session);
                source = store.integrated(sessionId, session.getFinalCommitSha()).map(op -> op.evidence().path("mergeCommit").asText())
                        .orElseThrow(() -> new DeliveryRejectedException("EXACT_CHANGE_NOT_INTEGRATED"));
                if (!source.equals(github.canonicalMain(APP))) throw new DeliveryRejectedException("CANONICAL_MAIN_MOVED");
            }
            return store.create(sessionId, actor.operatorId(), kind, target, source, "PLANNING", mapper.createObjectNode()).view();
        }));
    }

    public PrivilegedActionAuthorizationGrant authorize(UUID id, AuthenticatedSession actor, String totp) {
        enabled(); administrator(actor.operator().operatorId());
        DeliveryOperation plan = store.get(id, false);
        ready(plan, actor);
        try {
            return grants.issueVerified(factors.verifyTotpStepUp(actor.operator(), actor.sessionFamilyId(), binding(plan), totp));
        } catch (com.atenea.auth.OperatorAuthenticationException exception) {
            throw new DeliveryRejectedException("PUBLICATION_FACTOR_REJECTED");
        }
    }

    public DeliveryOperation.DeliveryView confirm(UUID id, AuthenticatedSession actor, UUID authorization) {
        enabled(); administrator(actor.operator().operatorId());
        return Objects.requireNonNull(transaction.execute(ignored -> {
            DeliveryOperation plan = store.get(id, true);
            if (!plan.operatorId().equals(actor.operator().operatorId())) throw new DeliveryRejectedException("OPERATION_OWNER_MISMATCH");
            // Replayed acceptance does not require consuming the same one-use grant twice.
            if (!List.of("READY", "PREPARING", "PLANNING").contains(plan.state())) return plan.view();
            ready(plan, actor);
            WorkSessionEntity session = session(plan.sessionId(), true);
            owned(session); idle(session.getId());
            if (plan.target() != DeliveryTarget.AX42_PLATFORM) {
                published(session);
                if (!plan.sourceCommit().equals(github.canonicalMain(APP))) throw new DeliveryRejectedException("CANONICAL_MAIN_MOVED");
            } else if (!plan.sourceCommit().equals(github.canonicalMain(PLATFORM))) {
                throw new DeliveryRejectedException("CANONICAL_MAIN_MOVED");
            }
            try {
                return grants.consumeForAcceptance(authorization, actor, binding(plan), () -> {
                    store.lockAdmissionAndRequireIdle();
                    store.update(plan, "CONFIRMED", null, plan.planSha256(), plan.evidence());
                    return store.get(id, false).view();
                });
            } catch (com.atenea.auth.OperatorAuthenticationException exception) {
                throw new DeliveryRejectedException("PUBLICATION_AUTHORIZATION_REJECTED");
            }
        }));
    }

    @Scheduled(scheduler = "mobileDeliveryScheduler", fixedDelayString = "${ATENEA_RELEASE_CONTROL_RECONCILE_DELAY_MS:3000}")
    public void reconcile() {
        if (!executor.enabled()) return;
        for (UUID id : store.pending()) {
            try {
                transaction.executeWithoutResult(ignored -> advance(store.get(id, true)));
            } catch (RuntimeException ignored) {
                // A transport/DB ambiguity is not failure and never creates a new identity.
                // The durable outbox is inspected/replayed on the next pass or App restart.
            }
        }
    }

    private void advance(DeliveryOperation op) {
        if (op.terminal()) return;
        if (op.state().equals("READY")) {
            if (op.evidence().path("expiresAt").asLong(0) <= Instant.now().getEpochSecond()) {
                store.update(op,"BLOCKED","PLAN_EXPIRED",op.planSha256(),op.evidence());
            }
            return;
        }
        if (!op.kind().equals("RELEASE")) {
            try {
                administrator(op.operatorId());
                WorkSessionEntity session = session(op.sessionId(), op.kind().equals("INTEGRATE"));
                owned(session); idle(session.getId());
                if (op.kind().equals("PUBLISH_PR")) {
                    // Publication owns its own short identity transactions; do not lock its
                    // WorkSession across its REQUIRES_NEW gateway receipt persistence.
                    var response = publication.publishForDelivery(op.sessionId());
                    store.update(op, "SUCCEEDED", null, null, mapper.createObjectNode()
                            .put("pullRequestUrl", response.pullRequestUrl()).put("headCommit", response.finalCommitSha()));
                } else {
                    published(session);
                    if (!Objects.equals(op.sourceCommit(), session.getFinalCommitSha())) throw new DeliveryRejectedException("PUBLISHED_HEAD_MOVED");
                    long number = github.extractPullRequestNumber(session.getPullRequestUrl());
                    String merge = github.integrateExact(APP, number, session.getPublishedHeadBranch(), op.sourceCommit());
                    session.setPullRequestStatus(WorkSessionPullRequestStatus.MERGED);
                    session.setIntegrationReadyAt(Instant.now());
                    sessions.saveAndFlush(session);
                    store.update(op, "SUCCEEDED", null, null, mapper.createObjectNode()
                            .put("pullRequestUrl", session.getPullRequestUrl()).put("mergeCommit", merge));
                }
            } catch (GitHubIntegrationException error) {
                String message = error.getMessage();
                if (message != null && (message.startsWith("UFD_PENDING:") || message.startsWith("CI_PENDING:"))) {
                    store.update(op, "WAITING_CI", "CI_PENDING", null, op.evidence());
                } else if (message != null && List.of("PR_OWNERSHIP_MISMATCH", "PR_CLOSED:",
                        "COMMIT_IDENTITY_INVALID", "CI_EVIDENCE_INCOMPLETE", "PR_READY_REJECTED", "MERGE_REJECTED",
                        "UFD_FAILED:", "CI_FAILED:").stream().anyMatch(message::startsWith)) {
                    store.update(op, "BLOCKED", "GITHUB_REJECTED", null, op.evidence());
                } else {
                    // The merge might have committed remotely before the response was lost.
                    // Keep its exact identity, observe GitHub again, never fabricate failure.
                    store.update(op, op.state(), "GITHUB_UNAVAILABLE", null, op.evidence());
                }
            } catch (DeliveryRejectedException error) {
                store.update(op, "BLOCKED", error.code(), null, op.evidence());
            }
            return;
        }
        try {
            JsonNode observed = switch (op.state()) {
                case "PLANNING" -> executor.plan(op.id(), op.target(), op.sourceCommit());
                case "CONFIRMED" -> executor.execute(op.id(), op.planSha256(), op.executionId());
                default -> executor.inspect(op.id());
            };
            boolean accepted = !List.of("PLANNING", "PREPARING", "READY").contains(op.state());
            ReleaseControlClient.verify(observed, op.id(), op.target(), op.sourceCommit(), op.planSha256(), op.executionId(), accepted);
            String state = observed.path("state").asText();
            store.update(op, state, observed.path("errorCode").isTextual() ? observed.path("errorCode").asText() : null,
                    observed.path("planSha256").isTextual() ? observed.path("planSha256").asText() : null, observed);
        } catch (DeliveryRejectedException error) {
            if (error.code().equals("RELEASE_TRANSPORT_UNAVAILABLE")) {
                store.update(op, op.state(), "RELEASE_TRANSPORT_UNAVAILABLE", op.planSha256(), op.evidence());
            } else {
                // A contradictory receipt is quarantined, not repaired or re-issued.
                store.update(op, List.of("PLANNING","PREPARING").contains(op.state()) ? "BLOCKED" : "QUARANTINED",
                        error.code(), op.planSha256(), op.evidence());
            }
        }
    }

    private WorkSessionEntity session(Long id, boolean lock) {
        return (lock ? sessions.findLockedWithProjectAndDevelopmentChangeById(id)
                     : sessions.findWithProjectAndDevelopmentChangeById(id))
                .orElseThrow(() -> new DeliveryRejectedException("WORK_SESSION_NOT_FOUND"));
    }
    private void owned(WorkSessionEntity session) {
        try { ownership.requireExactOwner(session); }
        catch (com.atenea.service.worksession.WorkSessionPublishConflictException exception) {
            throw new DeliveryRejectedException("VALIDATED_OWNERSHIP_REQUIRED");
        }
    }
    private void published(WorkSessionEntity session) {
        var change = session.getDevelopmentChange();
        if (change == null || !change.getChangeKey().equals(session.getPublishedChangeKey())
                || !Objects.equals(change.getSourceRevision(), session.getPublishedSourceRevision())
                || !Objects.equals(change.getSourceFingerprintSha256(), session.getPublishedSourceFingerprintSha256())
                || !Objects.equals(session.getSourceTreeFingerprintSha256(), session.getPublishedSourceFingerprintSha256())
                || !"jlnieto/atenea".equals(session.getPublishedRepository())
                || !"main".equals(session.getPublishedBaseBranch())
                || !Objects.equals(session.getWorkspaceBranch(), session.getPublishedHeadBranch())
                || session.getPullRequestUrl() == null || session.getFinalCommitSha() == null
                || !session.getFinalCommitSha().matches("[0-9a-f]{40}")) {
            throw new DeliveryRejectedException("PUBLISHED_OWNERSHIP_MISMATCH");
        }
    }
    private void ready(DeliveryOperation op, AuthenticatedSession actor) {
        if (!op.kind().equals("RELEASE") || !op.operatorId().equals(actor.operator().operatorId())
                || !op.state().equals("READY") || op.planSha256() == null
                || op.evidence().path("expiresAt").asLong(0) <= Instant.now().getEpochSecond()) {
            throw new DeliveryRejectedException("PLAN_NOT_READY_OR_EXPIRED");
        }
    }
    private PrivilegedActionBinding binding(DeliveryOperation op) {
        return PrivilegedActionBinding.fromCanonical("PUBLISH_ATENEA_RELEASE",
                op.target().name() + "|" + op.sourceCommit(), op.id() + "|" + op.planSha256());
    }
    private void administrator(Long id) {
        operators.findById(id).filter(candidate -> candidate.isActive()
                && candidate.getCodexOperationsRole() == CodexOperationsRole.PLATFORM_ADMINISTRATOR)
                .orElseThrow(() -> new DeliveryRejectedException("PLATFORM_ADMINISTRATOR_REQUIRED"));
    }
    private void idle(Long id) {
        if (runs.existsBySessionIdAndStatusIn(id, AgentRunStatus.nonTerminalStatuses())) throw new DeliveryRejectedException("ACTIVE_AGENT_RUN");
    }
    private void enabled() {
        if (!executor.enabled()) throw new DeliveryRejectedException("RELEASE_CONTROL_DISABLED");
    }
}
