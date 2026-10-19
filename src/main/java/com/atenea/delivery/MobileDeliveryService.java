package com.atenea.delivery;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.AuthenticatedSession;
import com.atenea.auth.action.PrivilegedActionAuthorizationGrant;
import com.atenea.auth.action.PrivilegedActionAuthorizationService;
import com.atenea.auth.action.PrivilegedActionBinding;
import com.atenea.auth.recovery.OperatorRecoveryService;
import com.atenea.github.GitHubClient;
import com.atenea.github.AndroidProductVersion;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.github.GitHubMergeState;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.WorkSessionAcceptanceState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.DevelopmentChangeBranchPublicationService;
import com.atenea.service.worksession.WorkSessionAcceptanceService;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final WorkSessionAcceptanceService acceptance;
    private final GitHubClient github;
    private final ReleaseControlClient executor;
    private final OperatorRecoveryService factors;
    private final PrivilegedActionAuthorizationService grants;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final SourceUpdateBaseGuard sourceBaseGuard;
    // Positive immutable ancestry proofs only. Runtime/health/role/owner are read on every poll.
    private final java.util.Map<String, Long> observedAncestry = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, List<DeliveryTarget>> immutableRequirements = new java.util.LinkedHashMap<>();
    private com.atenea.github.GitHubReleaseBuild builds;
    private ReleaseRepairStore repairs;
    private com.atenea.github.GitHubChecksProgress checksProgress;
    @org.springframework.beans.factory.annotation.Autowired
    void setChecksProgress(com.atenea.github.GitHubChecksProgress value) { checksProgress=value; }
    public record Progress(UUID operationId, Long sessionId, String kind, String state,
            long startedAt, long updatedAt, long observedAt, com.atenea.github.GitHubChecksProgress.Evidence github,
            ReleaseControlClient.StorageProgress storage) { }

    /** Informational only: never advances, retries or authorizes an operation. */
    public Progress observeProgress(Long sessionId, AuthenticatedOperator actor) {
        administrator(actor.operatorId());
        var session=session(sessionId,false);
        var operations=store.list(sessionId);
        var active=operations.stream().filter(op->!op.terminal() && !"READY".equals(op.state())).findFirst().orElse(null);
        if(active==null) active=operations.stream().filter(op->"RELEASE".equals(op.kind())).findFirst()
                .filter(op->java.util.Set.of("RELEASE_STORAGE_INSUFFICIENT","RETENTION_REQUIRES_INSPECTION","RETENTION_EVIDENCE_MISMATCH",
                        "RETENTION_RECOVERY_CONTENT_MISSING","RETENTION_CURRENT_RECEIPT_REQUIRED","RETENTION_RESOURCE_MOVED","RETENTION_PRODUCTION_MOVED")
                    .contains(Objects.toString(op.errorCode(),""))).orElse(null);
        if(active==null) {
            if(checksProgress==null) return null;
            if(session.getPullRequestStatus()!=WorkSessionPullRequestStatus.MERGED) {
                var published=operations.stream().filter(op->"PUBLISH_PR".equals(op.kind()) && "SUCCEEDED".equals(op.state())
                        && op.evidence().path("headCommit").asText().equals(session.getFinalCommitSha())).findFirst().orElse(null);
                if(published==null || session.getPullRequestUrl()==null || session.getPublishedHeadBranch()==null) return null;
                var evidence=progressChecks(session.getFinalCommitSha(),session.getPublishedHeadBranch(),false,true);
                if(evidence==null || "PASSED".equals(evidence.state())) return null;
                return new Progress(published.id(),sessionId,"PR_CHECKS","PENDING",published.createdAt().getEpochSecond(),
                        published.updatedAt().getEpochSecond(),Instant.now().getEpochSecond(),evidence,null);
            }
            var build=observeBuild(sessionId,actor);
            var integrated=store.integrated(sessionId,session.getFinalCommitSha()).orElse(null);
            if(build==null || build.evidence()==null || !"PENDING".equals(build.evidence().state()) || integrated==null) return null;
            return new Progress(integrated.id(),sessionId,"BUILD","PENDING",integrated.updatedAt().getEpochSecond(),
                    integrated.updatedAt().getEpochSecond(),Instant.now().getEpochSecond(),
                    progressChecks(build.evidence().sourceCommit(),"main",true,false),null);
        }
        com.atenea.github.GitHubChecksProgress.Evidence githubEvidence=null;
        if(checksProgress!=null && "WAITING_CI".equals(active.state())) {
            String head="ANDROID_VERSION".equals(active.kind()) ? active.evidence().path("headCommit").asText() : session.getFinalCommitSha();
            String branch="ANDROID_VERSION".equals(active.kind()) ? GitHubClient.androidVersionBranch(active.id()) : session.getPublishedHeadBranch();
            if(head!=null && branch!=null) githubEvidence=progressChecks(head,branch,false,
                    active.evidence().has("pullRequestUrl") || session.getPullRequestUrl()!=null);
        } else if(checksProgress!=null && List.of("PLANNING","PREPARING").contains(active.state()) && active.sourceCommit()!=null
                && active.target()!=DeliveryTarget.AX42_PLATFORM) {
            githubEvidence=progressChecks(active.sourceCommit(),"main",true,false);
        }
        return new Progress(active.id(),sessionId,active.kind(),active.state(),active.createdAt().getEpochSecond(),
                active.updatedAt().getEpochSecond(),Instant.now().getEpochSecond(),githubEvidence,storageProgress(active));
    }
    private ReleaseControlClient.StorageProgress storageProgress(DeliveryOperation operation) {
        if(!"RELEASE".equals(operation.kind()) || operation.target()==DeliveryTarget.AX42_PLATFORM
                || !List.of("PLANNING","PREPARING","BLOCKED").contains(operation.state())) return null;
        try { return ReleaseControlClient.verifyStorage(executor.observeStorage(operation.id()),operation.id(),operation.target(),operation.sourceCommit()); }
        catch(RuntimeException unavailable) { return null; } // Optional read-only projection, including older publishers.
    }
    private com.atenea.github.GitHubChecksProgress.Evidence progressChecks(String source,String branch,boolean build,boolean hasPr) {
        try { return checksProgress.observe(source,branch,build,hasPr); }
        catch(RuntimeException unavailable) {
            return source!=null && source.matches("[0-9a-f]{40}")
                    ? new com.atenea.github.GitHubChecksProgress.Evidence(source,"UNAVAILABLE",Instant.now().getEpochSecond(),"UNKNOWN",List.of()) : null;
        }
    }
    @org.springframework.beans.factory.annotation.Autowired
    void setBuildRecovery(com.atenea.github.GitHubReleaseBuild builds,ReleaseRepairStore repairs) {
        this.builds=builds;this.repairs=repairs;
    }
    private record CachedBuild(long expires,com.atenea.github.GitHubReleaseBuild.Evidence evidence) { }
    private final java.util.Map<String,CachedBuild> buildCache=new java.util.LinkedHashMap<>();
    public record BuildStatus(com.atenea.github.GitHubReleaseBuild.Evidence evidence,boolean canRepair,String errorCode) { }
    public BuildStatus observeBuild(Long sessionId,AuthenticatedOperator actor) {
        administrator(actor.operatorId());var session=session(sessionId,false);
        if(session.getPullRequestStatus()!=WorkSessionPullRequestStatus.MERGED || builds==null) return null;
        try {
            owned(session);published(session);
            String source=recoveryCanonicalMain();
            var integrated=store.integrated(sessionId,session.getFinalCommitSha()).orElseThrow();
            requireRecoveryAncestry(session,ReleaseRecoverySource.create(integrated,source));
            com.atenea.github.GitHubReleaseBuild.Evidence evidence;
            synchronized(buildCache) {
                var cached=buildCache.get(source);long now=System.currentTimeMillis();
                if(cached==null || cached.expires()<now) {
                    evidence=builds.observe(source);
                    if(buildCache.size()>=32) buildCache.remove(buildCache.keySet().iterator().next());
                    buildCache.put(source,new CachedBuild(now+15000,evidence));
                } else evidence=cached.evidence();
            }
            boolean failed="FAILED".equals(evidence.state());
            return new BuildStatus(evidence,failed && !runs.existsBySessionIdAndStatusIn(sessionId,AgentRunStatus.nonTerminalStatuses()),null);
        } catch(RuntimeException unavailable) { return new BuildStatus(null,false,"BUILD_STATUS_UNAVAILABLE"); }
    }

    SourceUpdateService.BuildRepair buildRepair(Long sessionId,AuthenticatedOperator actor) {
        enabled();administrator(actor.operatorId());var session=session(sessionId,false);
        var failed=store.list(sessionId).stream().filter(op -> "RELEASE".equals(op.kind())
            && actor.operatorId().equals(op.operatorId()) && "RELEASE_BUILD_FAILED".equals(op.errorCode()))
            .findFirst().orElse(null);
        var retained=failed==null ? null : repairs.failed(failed.id());
        if(retained!=null && retained.originalPrUrl().equals(session.getPullRequestUrl())) {
            if(!retained.operatorId().equals(actor.operatorId()) || !retained.sessionId().equals(sessionId)) throw new DeliveryRejectedException("RELEASE_REPAIR_OWNER_MISMATCH");
            try { return new SourceUpdateService.BuildRepair(failed,mapper.treeToValue(retained.diagnostic(),
                com.atenea.github.GitHubReleaseBuild.Evidence.class),retained.originalMerge(),repairs.retainedTargets(sessionId)); }
            catch(com.fasterxml.jackson.core.JsonProcessingException bad) { throw new DeliveryRejectedException("RELEASE_REPAIR_EVIDENCE_MISMATCH"); }
        }
        owned(session);idle(sessionId);published(session);
        String main=recoveryCanonicalMain();
        var integrated=store.integrated(sessionId,session.getFinalCommitSha()).orElseThrow(()->new DeliveryRejectedException("EXACT_CHANGE_NOT_INTEGRATED"));
        var proof=ReleaseRecoverySource.create(integrated,main);requireRecoveryAncestry(session,proof);
        var evidence=builds.observe(main); // Never authorize from the UI/poll cache.
        if(!"FAILED".equals(evidence.state()) || evidence.runId()==null
                || !main.equals(recoveryCanonicalMain())) throw new DeliveryRejectedException("RELEASE_REPAIR_SOURCE_MOVED");
        var readiness=observeDelivery(sessionId,actor);
        if(!"CONFIRMED".equals(readiness.requirementsStatus()) || readiness.ready()) throw new DeliveryRejectedException("RELEASE_REPAIR_EVIDENCE_MISMATCH");
        var exactFailure=transaction.execute(ignored->{
            store.lockReleaseIntent();store.lockAdmissionAndRequireIdle();
            if(store.activeRelease().isPresent()) throw new DeliveryRejectedException("RELEASE_IN_PROGRESS");
            var existing=store.list(sessionId).stream().filter(op->"RELEASE".equals(op.kind())
                && actor.operatorId().equals(op.operatorId()) && main.equals(op.sourceCommit())
                && "RELEASE_BUILD_FAILED".equals(op.errorCode())).findFirst();
            if(existing.isPresent()) return existing.get();
            // An explicit repair may be requested before any failed Prepare.
            // Record the authoritative CI failure, not a fabricated publication receipt.
            var entry=store.create(sessionId,actor.operatorId(),"RELEASE",DeliveryTarget.APP_PROD,main,"BLOCKED",
                mapper.createObjectNode().set("buildFailureObservation",mapper.valueToTree(evidence)));
            store.update(entry,"BLOCKED","RELEASE_BUILD_FAILED",null,entry.evidence());return store.get(entry.id(),false);
        });
        return new SourceUpdateService.BuildRepair(exactFailure,evidence,proof.integratedMergeCommit(),readiness.requiredTargets());
    }

    public MobileDeliveryService(DeliveryStore store, WorkSessionRepository sessions, AgentRunRepository runs,
            OperatorRepository operators, DevelopmentChangeBranchPublicationService ownership,
            WorkSessionGitHubService publication, WorkSessionAcceptanceService acceptance,
            GitHubClient github, ReleaseControlClient executor,
            OperatorRecoveryService factors, PrivilegedActionAuthorizationService grants,
            ObjectMapper mapper, PlatformTransactionManager transactionManager, SourceUpdateBaseGuard sourceBaseGuard) {
        this.store = store; this.sessions = sessions; this.runs = runs; this.operators = operators;
        this.ownership = ownership; this.publication = publication; this.github = github;
        this.acceptance = acceptance;
        this.executor = executor; this.factors = factors; this.grants = grants; this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.sourceBaseGuard=sourceBaseGuard;
    }

    public List<DeliveryOperation.DeliveryView> list(Long sessionId, AuthenticatedOperator actor) {
        administrator(actor.operatorId());
        session(sessionId, false);
        return store.list(sessionId).stream().map(DeliveryOperation::view).toList();
    }
    public boolean isEnabled() { return executor.enabled(); }
    WorkSessionEntity publishedSessionForNotice(DeliveryOperation op) {
        administrator(op.operatorId());
        var value=session(op.sessionId(),false); owned(value); published(value);
        if(!op.evidence().path("pullRequestUrl").asText().equals(value.getPullRequestUrl())
                || !op.evidence().path("headCommit").asText().equals(value.getFinalCommitSha()))
            throw new DeliveryRejectedException("PUBLISHED_HEAD_MOVED");
        return value;
    }

    /** Read-only global APK facts plus exact-change ancestry and diff-derived requirements. */
    public ChangeDeliveryReadiness observeDelivery(Long sessionId, AuthenticatedOperator actor) {
        administrator(actor.operatorId());
        WorkSessionEntity session=session(sessionId,false);
        ReleaseControlClient.AndroidObservation android=null;
        String androidError=null;
        try { android=ReleaseControlClient.verifyAndroidObservation(executor.observeAndroid()); }
        catch (DeliveryRejectedException unavailable) { androidError=unavailable.code(); }
        long now=Instant.now().getEpochSecond();
        if (session.getAcceptanceState()!=WorkSessionAcceptanceState.INTEGRATION_READY
                || session.getPullRequestStatus()!=WorkSessionPullRequestStatus.MERGED) {
            return new ChangeDeliveryReadiness(sessionId,"NOT_INTEGRATED",session.getFinalCommitSha(),null,List.of(),false,
                    null,android,"NOT_INTEGRATED",androidError,now);
        }
        List<DeliveryTarget> targets=List.of();
        String merge=null;
        try {
            enabled(); observedDeliveredOwner(session); published(session);
            var integrated=store.integrated(sessionId,session.getFinalCommitSha())
                    .orElseThrow(() -> new DeliveryRejectedException("EXACT_CHANGE_NOT_INTEGRATED"));
            if (!"INTEGRATE".equals(integrated.kind()) || !"SUCCEEDED".equals(integrated.state())
                    || integrated.target()!=DeliveryTarget.APP_PROD || !sessionId.equals(integrated.sessionId())
                    || !session.getFinalCommitSha().equals(integrated.sourceCommit())) throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
            merge=ReleaseRecoverySource.create(integrated,integrated.evidence().path("mergeCommit").asText()).integratedMergeCommit();
            requireCachedAncestry(session,ReleaseRecoverySource.create(integrated,merge));
            String requirementKey=session.getPullRequestUrl()+"|"+session.getPublishedHeadBranch()+"|"+session.getFinalCommitSha()+"|"+merge;
            synchronized (immutableRequirements) {
                targets=immutableRequirements.get(requirementKey);
                if (targets==null) {
                    targets=ChangeDeliveryReadiness.classify(github.changedFiles(APP,github.extractPullRequestNumber(session.getPullRequestUrl()),
                            session.getPublishedHeadBranch(),session.getFinalCommitSha()));
                    if (immutableRequirements.size()>=128) immutableRequirements.remove(immutableRequirements.keySet().iterator().next());
                    immutableRequirements.put(requirementKey,targets);
                }
            }
            if(repairs!=null) {
                var retained=new java.util.TreeSet<DeliveryTarget>(targets);retained.addAll(repairs.retainedTargets(sessionId));
                targets=List.copyOf(retained);
            }
            String androidStatus=android==null ? "UNAVAILABLE" : "NOT_INCLUDED";
            if (android!=null) {
                try { requireCachedAncestry(session,ReleaseRecoverySource.create(integrated,android.sourceCommit())); androidStatus="INCLUDED"; }
                catch (DeliveryRejectedException rejected) {
                    if (!"RELEASE_CHANGE_NOT_INCLUDED".equals(rejected.code())) { androidStatus="UNAVAILABLE"; androidError=rejected.code(); }
                }
            }
            var backend=targets.contains(DeliveryTarget.APP_PROD) ? observeDeployment(sessionId,actor) : null;
            boolean ready=(!targets.contains(DeliveryTarget.APP_PROD) || backend!=null && "DEPLOYED".equals(backend.status()) && backend.healthy())
                    && (!targets.contains(DeliveryTarget.ANDROID_STABLE) || "INCLUDED".equals(androidStatus));
            return new ChangeDeliveryReadiness(sessionId,"CONFIRMED",session.getFinalCommitSha(),merge,targets,ready,
                    backend,android,androidStatus,backend!=null && backend.errorCode()!=null ? backend.errorCode()
                        : targets.contains(DeliveryTarget.ANDROID_STABLE) ? androidError : null,now);
        } catch (DeliveryRejectedException | GitHubIntegrationException rejected) {
            return new ChangeDeliveryReadiness(sessionId,"UNAVAILABLE",session.getFinalCommitSha(),merge,targets==null ? List.of() : targets,false,
                    null,android,"UNAVAILABLE",rejected instanceof DeliveryRejectedException d ? d.code() : "DELIVERY_DIFF_UNAVAILABLE",now);
        }
    }

    /** Read-only installed state; does not adopt operator effects into the mobile outbox. */
    public DeploymentObservation observeDeployment(Long sessionId, AuthenticatedOperator actor) {
        administrator(actor.operatorId());
        WorkSessionEntity session = session(sessionId, false);
        if (session.getAcceptanceState() != WorkSessionAcceptanceState.INTEGRATION_READY
                || session.getPullRequestStatus() != WorkSessionPullRequestStatus.MERGED) {
            return DeploymentObservation.unavailable(sessionId, "NOT_INTEGRATED", null);
        }
        try {
            enabled(); observedDeliveredOwner(session); published(session);
            DeliveryOperation integrated = store.integrated(sessionId, session.getFinalCommitSha())
                    .orElseThrow(() -> new DeliveryRejectedException("EXACT_CHANGE_NOT_INTEGRATED"));
            if (!"INTEGRATE".equals(integrated.kind()) || !"SUCCEEDED".equals(integrated.state())
                    || integrated.target() != DeliveryTarget.APP_PROD || !sessionId.equals(integrated.sessionId())
                    || !session.getFinalCommitSha().equals(integrated.sourceCommit())) {
                throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
            }
            var observed = ReleaseControlClient.verifyAppObservation(executor.observeApp());
            var proof = ReleaseRecoverySource.create(integrated, observed.sourceCommit());
            requireCachedAncestry(session,proof);
            var mobile = store.find(observed.planId());
            String origin = "OPERATOR";
            if (mobile.isPresent()) {
                var release = mobile.get();
                if (!"RELEASE".equals(release.kind()) || release.target() != DeliveryTarget.APP_PROD
                        || !observed.operationId().equals(release.executionId())
                        || !observed.sourceCommit().equals(release.sourceCommit())) {
                    throw new DeliveryRejectedException("APP_OBSERVATION_EVIDENCE_MISMATCH");
                }
                origin = "MOBILE";
            }
            return new DeploymentObservation(sessionId, observed.healthy() ? "DEPLOYED" : "UNHEALTHY", origin,
                    observed.sourceCommit(), proof.integratedMergeCommit(), observed.healthy(), observed.observedAt(),
                    observed.planId(), observed.operationId(), observed.receiptSha256(), null);
        } catch (DeliveryRejectedException rejected) {
            return DeploymentObservation.unavailable(sessionId,
                    "RELEASE_CHANGE_NOT_INCLUDED".equals(rejected.code()) ? "NOT_INCLUDED" : "UNAVAILABLE", rejected.code());
        }
    }
    public record DeploymentObservation(Long sessionId, String status, String origin, String sourceCommit,
            String integratedMergeCommit, boolean healthy, Long observedAt, UUID planId, UUID operationId,
            String receiptSha256, String errorCode) {
        static DeploymentObservation unavailable(Long id, String status, String error) {
            return new DeploymentObservation(id, status, null, null, null, false, null, null, null, null, error);
        }
    }

    /** Explicit alternative when canonical main advanced after this ticket was integrated. */
    public DeliveryOperation.DeliveryView requestReleaseRecovery(Long sessionId, AuthenticatedOperator actor) {
        return requestReleaseRecovery(sessionId,actor,DeliveryTarget.APP_PROD);
    }
    public DeliveryOperation.DeliveryView requestReleaseRecovery(Long sessionId, AuthenticatedOperator actor, DeliveryTarget target) {
        if (!List.of(DeliveryTarget.APP_PROD,DeliveryTarget.ANDROID_STABLE).contains(target)) throw new DeliveryRejectedException("CLOSED_REQUEST_REQUIRED");
        enabled(); administrator(actor.operatorId());
        return Objects.requireNonNull(transaction.execute(ignored -> {
            WorkSessionEntity session = session(sessionId, true);
            owned(session); idle(sessionId); published(session);
            if (session.getAcceptanceState() != WorkSessionAcceptanceState.INTEGRATION_READY
                    || session.getPullRequestStatus() != WorkSessionPullRequestStatus.MERGED) {
                throw new DeliveryRejectedException("EXACT_CHANGE_NOT_INTEGRATED");
            }
            store.lockReleaseIntent();
            var active = store.activeRelease();
            if (active.isPresent()) {
                DeliveryOperation retained = active.get();
                if (retained.sessionId().equals(sessionId) && retained.operatorId().equals(actor.operatorId())
                        && retained.target()==target
                        && ReleaseRecoverySource.from(retained) != null) {
                    requireRecoveryIdentity(retained, session);
                    return retained.view(); // Retain its pinned source even if main moved again.
                }
                throw new DeliveryRejectedException("RELEASE_IN_PROGRESS");
            }
            DeliveryOperation integrated = store.integrated(sessionId, session.getFinalCommitSha())
                    .orElseThrow(() -> new DeliveryRejectedException("EXACT_CHANGE_NOT_INTEGRATED"));
            if (!"INTEGRATE".equals(integrated.kind()) || !"SUCCEEDED".equals(integrated.state())
                    || integrated.target() != DeliveryTarget.APP_PROD || !integrated.sessionId().equals(sessionId)
                    || !Objects.equals(integrated.sourceCommit(), session.getFinalCommitSha())) {
                throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
            }
            String selected = recoveryCanonicalMain();
            ReleaseRecoverySource proof = ReleaseRecoverySource.create(integrated, selected);
            var completed = target==DeliveryTarget.APP_PROD ? store.completedRecovery(sessionId, actor.operatorId(), integrated.id(), selected)
                    : store.list(sessionId).stream().filter(op -> "RELEASE".equals(op.kind()) && "SUCCEEDED".equals(op.state())
                        && op.target()==target && selected.equals(op.sourceCommit()) && actor.operatorId().equals(op.operatorId())
                        && ReleaseRecoverySource.from(op)!=null && integrated.id().equals(ReleaseRecoverySource.from(op).integrationOperationId())).findFirst();
            if (completed.isPresent()) {
                requireRecoveryIdentity(completed.get(), session);
                return completed.get().view();
            }
            requireRecoveryAncestry(session, proof);
            var failed=store.list(sessionId).stream().filter(op -> "RELEASE".equals(op.kind()) && op.target()==target
                && actor.operatorId().equals(op.operatorId()) && selected.equals(op.sourceCommit())
                && "RELEASE_BUILD_FAILED".equals(op.errorCode())).findFirst();
            if(failed.isPresent() && builds!=null) {
                if("FAILED".equals(builds.observe(selected).state())) return failed.get().view();
            }
            return store.create(sessionId, actor.operatorId(), "RELEASE", target, selected,
                    "PLANNING", mapper.createObjectNode().set(ReleaseRecoverySource.FIELD, proof.json(mapper))).view();
        }));
    }

    /** Empty mobile request: the server pins main, original integration and the verified installed channel. */
    public DeliveryOperation.DeliveryView requestAndroidVersion(Long sessionId, AuthenticatedOperator actor) {
        enabled(); administrator(actor.operatorId());
        return Objects.requireNonNull(transaction.execute(ignored -> {
            WorkSessionEntity session = session(sessionId, true);
            owned(session); idle(sessionId); published(session);
            store.lockReleaseIntent();
            var active = store.activeRelease();
            if (active.isPresent()) {
                DeliveryOperation retained = active.get();
                if ("ANDROID_VERSION".equals(retained.kind()) && retained.sessionId().equals(sessionId)
                        && retained.operatorId().equals(actor.operatorId())) { requireVersionIdentity(retained, session); return retained.view(); }
                throw new DeliveryRejectedException("RELEASE_IN_PROGRESS");
            }
            if (session.getAcceptanceState()!=WorkSessionAcceptanceState.INTEGRATION_READY
                    || session.getPullRequestStatus()!=WorkSessionPullRequestStatus.MERGED) throw new DeliveryRejectedException("EXACT_CHANGE_NOT_INTEGRATED");
            DeliveryOperation integrated = store.integrated(sessionId, session.getFinalCommitSha())
                    .orElseThrow(() -> new DeliveryRejectedException("EXACT_CHANGE_NOT_INTEGRATED"));
            if (!"INTEGRATE".equals(integrated.kind()) || !"SUCCEEDED".equals(integrated.state())
                    || integrated.target()!=DeliveryTarget.APP_PROD || !sessionId.equals(integrated.sessionId())
                    || !session.getFinalCommitSha().equals(integrated.sourceCommit())) throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
            String source = recoveryCanonicalMain();
            ReleaseRecoverySource proof = ReleaseRecoverySource.create(integrated, source);
            requireRecoveryAncestry(session, proof);
            var observed = ReleaseControlClient.verifyAndroidObservation(executor.observeAndroid());
            try {
                AndroidProductVersion declared = github.androidProductVersion(source);
                AndroidProductVersion channel = new AndroidProductVersion(observed.versionCode(), observed.versionName());
                AndroidProductVersion next = declared.code()>channel.code() ? declared : declared.nextAfter(channel);
                if (declared.code()<=channel.code() && !next.equals(declared.nextAfter(declared))) throw new DeliveryRejectedException("ANDROID_VERSION_POLICY_REJECTED");
                ObjectNode evidence = mapper.createObjectNode().put("versionCode", next.code()).put("versionName", next.name());
                evidence.set("versionSource", proof.json(mapper));
                evidence.set("versionPredecessor", versionPredecessor(observed));
                var retained = store.list(sessionId).stream().filter(op -> "ANDROID_VERSION".equals(op.kind())
                        && op.operatorId().equals(actor.operatorId()) && source.equals(op.sourceCommit())
                        && op.terminal() && op.evidence().path("versionSource").equals(evidence.path("versionSource"))
                        && op.evidence().path("versionPredecessor").equals(evidence.path("versionPredecessor"))).findFirst();
                if (retained.isPresent()) return retained.get().view();
                return store.create(sessionId, actor.operatorId(), "ANDROID_VERSION", DeliveryTarget.ANDROID_STABLE, source,
                        declared.code()>channel.code() ? "SUCCEEDED" : "QUEUED", evidence).view();
            } catch (GitHubIntegrationException rejected) { throw new DeliveryRejectedException(rejected.getMessage()); }
        }));
    }

    public DeliveryOperation.DeliveryView integrateAndroidVersion(Long sessionId, UUID id, AuthenticatedOperator actor) {
        enabled(); administrator(actor.operatorId());
        return Objects.requireNonNull(transaction.execute(ignored -> {
            DeliveryOperation op = store.get(id, true);
            if (!sessionId.equals(op.sessionId()) || !actor.operatorId().equals(op.operatorId()) || !"ANDROID_VERSION".equals(op.kind()))
                throw new DeliveryRejectedException("OPERATION_OWNER_MISMATCH");
            WorkSessionEntity session = session(sessionId, true); owned(session); idle(sessionId); published(session);
            requireVersionIdentity(op, session);
            if (!"READY".equals(op.state())) return op.view(); // Replays cannot initiate a second merge.
            store.update(op, "ACCEPTED", null, null, op.evidence());
            return store.get(id, false).view();
        }));
    }

    private ObjectNode versionPredecessor(ReleaseControlClient.AndroidObservation observed) {
        return mapper.createObjectNode().put("sourceCommit", observed.sourceCommit()).put("versionCode", observed.versionCode())
                .put("versionName", observed.versionName()).put("apkSha256", observed.apkSha256())
                .put("certificateSha256", observed.certificateSha256()).put("planId", observed.planId().toString())
                .put("operationId", observed.operationId().toString()).put("receiptSha256", observed.receiptSha256());
    }
    private void requireVersionIdentity(DeliveryOperation op, WorkSessionEntity session) {
        if (op.target()!=DeliveryTarget.ANDROID_STABLE || session.getAcceptanceState()!=WorkSessionAcceptanceState.INTEGRATION_READY
                || session.getPullRequestStatus()!=WorkSessionPullRequestStatus.MERGED) throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
        var integration = store.integrated(session.getId(), session.getFinalCommitSha())
                .orElseThrow(() -> new DeliveryRejectedException("EXACT_CHANGE_NOT_INTEGRATED"));
        var expected = ReleaseRecoverySource.create(integration, op.sourceCommit());
        if (!"INTEGRATE".equals(integration.kind()) || !"SUCCEEDED".equals(integration.state()) || integration.target()!=DeliveryTarget.APP_PROD
                || !session.getId().equals(integration.sessionId()) || !expected.json(mapper).equals(op.evidence().path("versionSource")))
            throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
        requireRecoveryAncestry(session, expected);
    }

    private void advanceAndroidVersion(DeliveryOperation op) {
        try {
            administrator(op.operatorId());
            WorkSessionEntity session = session(op.sessionId(), false); owned(session); idle(op.sessionId()); published(session);
            requireVersionIdentity(op, session);
            ObjectNode evidence = op.evidence().deepCopy();
            JsonNode predecessor = evidence.path("versionPredecessor");
            AndroidProductVersion channel = new AndroidProductVersion(predecessor.path("versionCode").asInt(), predecessor.path("versionName").asText());
            String head = evidence.path("headCommit").asText();
            if ("ACCEPTED".equals(op.state())) {
                // Observe a lost merge response before testing whether main advanced.
                var pr = github.getPullRequest(APP, github.extractPullRequestNumber(evidence.path("pullRequestUrl").asText()));
                if (pr.merged()) {
                    github.requireAndroidVersionHead(op.sourceCommit(), head, channel);
                    String merge = github.integrateExact(APP, pr.number(), GitHubClient.androidVersionBranch(op.id()), head);
                    store.update(op, "SUCCEEDED", null, null, evidence.put("mergeCommit", merge)); return;
                }
            }
            if (!op.sourceCommit().equals(recoveryCanonicalMain())) throw new DeliveryRejectedException("CANONICAL_MAIN_MOVED");
            if (!predecessor.equals(versionPredecessor(ReleaseControlClient.verifyAndroidObservation(executor.observeAndroid()))))
                throw new DeliveryRejectedException("ANDROID_CHANNEL_MOVED");
            if ("QUEUED".equals(op.state())) {
                head = github.prepareAndroidVersion(op.id(), op.sourceCommit(), channel, op.createdAt());
                store.update(op, "WAITING_CI", null, null, evidence.put("headCommit", head)); return;
            }
            github.requireAndroidVersionHead(op.sourceCommit(), head, channel);
            if ("WAITING_CI".equals(op.state())) {
                var pr = github.androidVersionPullRequest(op.id(), head,
                        new AndroidProductVersion(evidence.path("versionCode").asInt(), evidence.path("versionName").asText()));
                store.update(op, "READY", null, null, evidence.put("pullRequestUrl", pr.htmlUrl()));
            } else if ("ACCEPTED".equals(op.state())) {
                String merge = github.integrateExact(APP, github.extractPullRequestNumber(evidence.path("pullRequestUrl").asText()),
                        GitHubClient.androidVersionBranch(op.id()), head);
                store.update(op, "SUCCEEDED", null, null, evidence.put("mergeCommit", merge));
            }
        } catch (DeliveryRejectedException rejected) {
            if (!"RELEASE_TRANSPORT_UNAVAILABLE".equals(rejected.code()) && !"GITHUB_UNAVAILABLE".equals(rejected.code()))
                store.update(op, "BLOCKED", rejected.code(), null, op.evidence());
            else store.update(op, op.state(), rejected.code(), null, op.evidence());
        } catch (GitHubIntegrationException failure) {
            String message = failure.getMessage()==null ? "" : failure.getMessage();
            if (List.of("ANDROID_VERSION_POLICY_REJECTED","ANDROID_VERSION_IDENTITY_MISMATCH","ANDROID_VERSION_PR_CLOSED","CANONICAL_MAIN_MOVED").contains(message))
                store.update(op, "BLOCKED", message, null, op.evidence());
            else if (message.startsWith("UFD_FAILED:") || message.startsWith("CI_FAILED:") || message.startsWith("PR_MERGE_CONFLICTS:") || message.startsWith("PR_PROTECTED:"))
                store.update(op, "BLOCKED", "GITHUB_CHECKS_FAILED", null, op.evidence());
            else if (message.startsWith("UFD_NOT_STARTED:") && Instant.now().isAfter(op.createdAt().plusSeconds(600)))
                store.update(op, "BLOCKED", "UFD_NOT_STARTED", null, op.evidence());
            else if (List.of("UFD_PENDING:","UFD_QUEUED:","UFD_NOT_STARTED:","CI_PENDING:","PR_MERGEABILITY_PENDING:").stream().anyMatch(message::startsWith))
                store.update(op, op.state(), "CI_PENDING", null, op.evidence());
            else if (List.of("PR_OWNERSHIP_MISMATCH","COMMIT_IDENTITY_INVALID","CI_EVIDENCE_INCOMPLETE","PR_CLOSED:","MERGE_REJECTED").stream().anyMatch(message::startsWith))
                store.update(op, "BLOCKED", "ANDROID_VERSION_IDENTITY_MISMATCH", null, op.evidence());
            else store.update(op, op.state(), "GITHUB_UNAVAILABLE", null, op.evidence());
        }
    }

    public IntegrationObservation observeIntegration(Long sessionId, AuthenticatedOperator actor) {
        administrator(actor.operatorId());
        WorkSessionEntity session = session(sessionId, false);
        if (session.getPullRequestUrl() == null) return new IntegrationObservation(sessionId, null, "NOT_PUBLISHED", false, null);
        try {
            if (session.getAcceptanceState() == WorkSessionAcceptanceState.INTEGRATION_READY) observedDeliveredOwner(session);
            else owned(session);
            published(session);
            GitHubMergeState state = github.observeMergeState(APP,
                    github.extractPullRequestNumber(session.getPullRequestUrl()),
                    session.getPublishedHeadBranch(), session.getFinalCommitSha());
            if (state == null) return new IntegrationObservation(sessionId, session.getFinalCommitSha(), "UNAVAILABLE", false, "GITHUB_UNAVAILABLE");
            return new IntegrationObservation(sessionId, session.getFinalCommitSha(), state.name(),
                    state == GitHubMergeState.MERGEABLE, null);
        } catch (DeliveryRejectedException rejected) {
            return new IntegrationObservation(sessionId, session.getFinalCommitSha(), "STALE_SOURCE", false, rejected.code());
        } catch (GitHubIntegrationException unavailable) {
            return new IntegrationObservation(sessionId, session.getFinalCommitSha(), "UNAVAILABLE", false, "GITHUB_UNAVAILABLE");
        }
    }

    /** This permits requesting the normal checked integration, not bypassing CI or review. */
    public record IntegrationObservation(Long sessionId, String sourceCommit, String mergeState,
            boolean canRequestIntegration, String errorCode) { }

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
                    if (session.getPullRequestStatus() != null && session.getPullRequestStatus() != WorkSessionPullRequestStatus.NOT_CREATED
                            && !(session.getPullRequestStatus()==WorkSessionPullRequestStatus.MERGED && repairs!=null
                                && repairs.replacement(session,session.getFinalCommitSha())!=null)
                            && Objects.equals(session.getDevelopmentChange().getSourceRevision(), session.getPublishedSourceRevision())
                            && Objects.equals(session.getDevelopmentChange().getSourceFingerprintSha256(),session.getPublishedSourceFingerprintSha256())) {
                        var receipt = store.list(sessionId).stream().filter(op -> op.kind().equals(kind) && op.state().equals("SUCCEEDED")
                            && Objects.equals(op.evidence().path("headCommit").asText(),session.getFinalCommitSha())).findFirst();
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
                if ("PUBLISH_PR".equals(kind)) sourceBaseGuard.requireCurrent(sessionId);
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
        DeliveryOperation plan = authorizationPlan(id, actor);
        try {
            return grants.issueVerified(factors.verifyTotpStepUp(actor.operator(), actor.sessionFamilyId(), binding(plan), totp));
        } catch (com.atenea.auth.OperatorAuthenticationException exception) {
            throw new DeliveryRejectedException("PUBLICATION_FACTOR_REJECTED");
        }
    }

    /** Both factors consume the same server-derived publication identity and readiness policy. */
    DeliveryOperation authorizationPlan(UUID id, AuthenticatedSession actor) {
        enabled(); administrator(actor.operator().operatorId());
        DeliveryOperation plan = store.get(id, false);
        ready(plan, actor);
        if (ReleaseRecoverySource.from(plan) != null) {
            String blocked = transaction.execute(ignored -> {
                DeliveryOperation retained = store.get(id, true);
                ready(retained, actor);
                WorkSessionEntity session = session(retained.sessionId(), true);
                owned(session); idle(session.getId()); published(session);
                try { requireRecoverySource(retained, session); }
                catch (DeliveryRejectedException rejected) {
                    if (!"CANONICAL_MAIN_MOVED".equals(rejected.code())) throw rejected;
                    store.update(retained, "BLOCKED", rejected.code(), retained.planSha256(), retained.evidence());
                    return rejected.code();
                }
                return null;
            });
            if (blocked != null) throw new DeliveryRejectedException(blocked);
        }
        return plan;
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
            boolean recovery = ReleaseRecoverySource.from(plan) != null;
            try {
                if (plan.target() != DeliveryTarget.AX42_PLATFORM) {
                    published(session);
                    if (recovery) requireRecoverySource(plan, session);
                    else if (!plan.sourceCommit().equals(github.canonicalMain(APP))) throw new DeliveryRejectedException("CANONICAL_MAIN_MOVED");
                } else if (!plan.sourceCommit().equals(github.canonicalMain(PLATFORM))) {
                    throw new DeliveryRejectedException("CANONICAL_MAIN_MOVED");
                }
            } catch (DeliveryRejectedException rejected) {
                if (!recovery || !"CANONICAL_MAIN_MOVED".equals(rejected.code())) throw rejected;
                store.update(plan, "BLOCKED", rejected.code(), plan.planSha256(), plan.evidence());
                return store.get(id, false).view();
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
                GitHubClient.UfdDispatchRequest dispatch = transaction.execute(ignored -> {
                    DeliveryOperation op = store.get(id, true);
                    if (!op.terminal() && "PREPARED".equals(op.evidence().path("ufdDispatch").path("status").asText())) {
                        return claimUfdDispatch(op);
                    }
                    advance(op);
                    return null;
                });
                // The claim has COMMITTED before HTTP. A crash/lost reply is observed,
                // never blindly re-dispatched by a poll, App restart or duplicate tap.
                if (dispatch != null) sendClaimedUfdDispatch(id, dispatch);
            } catch (RuntimeException ignored) {
                // A transport/DB ambiguity is not failure and never creates a new identity.
                // The durable outbox is inspected/replayed on the next pass or App restart.
            }
        }
    }

    private GitHubClient.UfdDispatchRequest claimUfdDispatch(DeliveryOperation op) {
        try {
            administrator(op.operatorId());
            WorkSessionEntity session = session(op.sessionId(), false);
            owned(session); idle(op.sessionId()); publishedHead(session);
            requireDispatchHead(op, session);
        } catch (DeliveryRejectedException rejected) {
            store.update(op, "BLOCKED", rejected.code(), null, op.evidence());
            return null;
        }
        JsonNode record = op.evidence().path("ufdDispatch");
        if (!record.isObject() || !record.path("headSha").asText().matches("[0-9a-f]{40}")
                || !record.path("authoritySha").asText().matches("[0-9a-f]{40}")
                || !GitHubClient.ufdRequestId(record.path("headSha").asText(), record.path("headBranch").asText())
                        .toString().equals(record.path("requestId").asText())) {
            store.update(op, "BLOCKED", "UFD_IDENTITY_REJECTED", null, op.evidence());
            return null;
        }
        var request = new GitHubClient.UfdDispatchRequest(UUID.fromString(record.path("requestId").asText()),
                record.path("headSha").asText(), record.path("headBranch").asText(), record.path("authoritySha").asText());
        ObjectNode evidence = op.evidence().deepCopy();
        ((ObjectNode) evidence.path("ufdDispatch")).put("status", "CLAIMED")
                .put("claimedAt", Instant.now().getEpochSecond());
        store.update(op, "WAITING_CI", "UFD_REQUESTED", null, evidence);
        return request;
    }

    private void sendClaimedUfdDispatch(UUID id, GitHubClient.UfdDispatchRequest request) {
        boolean accepted;
        try { github.dispatchOwnedHeadUfd(request); accepted = true; }
        catch (GitHubIntegrationException error) { accepted = false; }
        final boolean confirmed = accepted;
        transaction.executeWithoutResult(ignored -> {
            DeliveryOperation op = store.get(id, true);
            if (op.terminal() || !request.requestId().toString().equals(op.evidence().path("ufdDispatch").path("requestId").asText())) return;
            ObjectNode evidence = op.evidence().deepCopy();
            ((ObjectNode) evidence.path("ufdDispatch")).put("status", confirmed ? "ACCEPTED" : "UNCONFIRMED");
            store.update(op, "WAITING_CI", confirmed ? "UFD_REQUESTED" : "UFD_DISPATCH_UNCONFIRMED", null, evidence);
        });
    }

    private void prepareUfdDispatch(DeliveryOperation op, WorkSessionEntity session) {
        publishedHead(session);
        var previous = store.ownedHeadUfdRequest(op.sessionId(), session.getFinalCommitSha(), session.getPublishedHeadBranch());
        if (previous.isPresent()) {
            ObjectNode retained = op.evidence().deepCopy();
            retained.set("ufdDispatch", previous.get().evidence().path("ufdDispatch").deepCopy());
            store.update(op, "WAITING_CI", "UFD_DISPATCH_UNCONFIRMED", null, retained);
            return;
        }
        GitHubClient.UfdDispatchRequest request = github.prepareOwnedHeadUfd(
                session.getFinalCommitSha(), session.getPublishedHeadBranch());
        ObjectNode evidence = op.evidence().deepCopy();
        evidence.set("ufdDispatch", mapper.valueToTree(request));
        ((ObjectNode) evidence.path("ufdDispatch")).put("status", "PREPARED");
        store.update(op, "WAITING_CI", "UFD_REQUESTED", null, evidence);
    }

    private void requireDispatchHead(DeliveryOperation op, WorkSessionEntity session) {
        JsonNode record = op.evidence().path("ufdDispatch");
        if (!"PUBLISH_PR".equals(op.kind()) || op.target() != DeliveryTarget.APP_PROD
                || !Objects.equals(session.getFinalCommitSha(), record.path("headSha").asText())
                || !Objects.equals(session.getPublishedHeadBranch(), record.path("headBranch").asText())) {
            throw new DeliveryRejectedException("UFD_IDENTITY_REJECTED");
        }
    }

    private void waitForUfd(DeliveryOperation op, String code) {
        // Missing/ambiguous initiation is not "GitHub is running" forever.
        long claimedAt = op.evidence().path("ufdDispatch").path("claimedAt").asLong(op.createdAt().getEpochSecond());
        if (Instant.now().getEpochSecond() - claimedAt > 600 && !"CI_PENDING".equals(code)) {
            store.update(op, "BLOCKED", "UFD_NOT_STARTED", null, op.evidence());
        } else {
            store.update(op, "WAITING_CI", code, null, op.evidence());
        }
    }

    private void advance(DeliveryOperation op) {
        if (op.terminal()) return;
        if ("ANDROID_VERSION".equals(op.kind())) { advanceAndroidVersion(op); return; }
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
                if (op.evidence().has("ufdDispatch")) {
                    publishedHead(session); requireDispatchHead(op, session);
                }
                if (op.kind().equals("PUBLISH_PR")) {
                    // Publication owns its own short identity transactions; do not lock its
                    // WorkSession across its REQUIRES_NEW gateway receipt persistence.
                    var response = publication.publishForDelivery(op.sessionId());
                    ObjectNode receipt = op.evidence().deepCopy();
                    receipt.put("pullRequestUrl", response.pullRequestUrl()).put("headCommit", response.finalCommitSha());
                    if (receipt.path("ufdDispatch").isObject()) {
                        ((ObjectNode) receipt.path("ufdDispatch")).put("resultObserved", "PASSED");
                    }
                    store.update(op, "SUCCEEDED", null, null, receipt);
                } else {
                    published(session);
                    if (!Objects.equals(op.sourceCommit(), session.getFinalCommitSha())) throw new DeliveryRejectedException("PUBLISHED_HEAD_MOVED");
                    long number = github.extractPullRequestNumber(session.getPullRequestUrl());
                    String merge = github.integrateExact(APP, number, session.getPublishedHeadBranch(), op.sourceCommit());
                    // GitHub may already have merged before a lost reply or DB rollback.
                    // Promote the exact validated projection and its timestamp together,
                    // in the same transaction as the retained integration receipt.
                    if (session.getAcceptanceState() == WorkSessionAcceptanceState.VALIDATED) {
                        acceptance.markIntegrationReady(session.getId(), session.getSourceTreeFingerprintSha256(),
                                session.getValidationProjectionSha256(), session.getValidationDefinitionRevision());
                    }
                    session.setPullRequestStatus(WorkSessionPullRequestStatus.MERGED);
                    sessions.saveAndFlush(session);
                    store.update(op, "SUCCEEDED", null, null, mapper.createObjectNode()
                            .put("pullRequestUrl", session.getPullRequestUrl()).put("mergeCommit", merge));
                }
            } catch (GitHubIntegrationException error) {
                String message = error.getMessage();
                if (message != null && message.startsWith("UFD_WORKFLOW_MISSING:")) {
                    if (op.evidence().has("ufdDispatch")) {
                        waitForUfd(op, "UFD_REQUESTED");
                    } else if ("PUBLISH_PR".equals(op.kind())) {
                        try { prepareUfdDispatch(op, session(op.sessionId(), false)); }
                        catch (GitHubIntegrationException unavailable) {
                            if ("UFD_CONTROLLER_UNAVAILABLE".equals(unavailable.getMessage())) {
                                store.update(op, "BLOCKED", "UFD_CONTROLLER_UNAVAILABLE", null, op.evidence());
                            } else throw unavailable;
                        } catch (DeliveryRejectedException rejected) {
                            store.update(op, "BLOCKED", rejected.code(), null, op.evidence());
                        }
                    } else store.update(op, "BLOCKED", "UFD_NOT_STARTED", null, op.evidence());
                } else if (message != null && message.startsWith("UFD_NOT_STARTED:")) {
                    waitForUfd(op, "UFD_NOT_STARTED");
                } else if (message != null && message.startsWith("UFD_QUEUED:")) {
                    store.update(op, "WAITING_CI", "UFD_QUEUED", null, op.evidence());
                } else if (message != null && message.startsWith("PR_MERGEABILITY_PENDING:")) {
                    store.update(op, "WAITING_CI", "PR_MERGEABILITY_PENDING", null, op.evidence());
                } else if (message != null && message.startsWith("PR_MERGE_CONFLICTS:")) {
                    store.update(op, "BLOCKED", "PR_MERGE_CONFLICTS", null, op.evidence());
                } else if(message!=null && message.startsWith("PR_BASE_VALIDATION_MOVED:")) {
                    store.update(op,"BLOCKED","PR_BASE_VALIDATION_MOVED",null,op.evidence());
                } else if (message != null && message.startsWith("PR_PROTECTED:")) {
                    store.update(op, "BLOCKED", "PR_PROTECTED", null, op.evidence());
                } else if (message != null && (message.startsWith("UFD_FAILED:") || message.startsWith("CI_FAILED:"))) {
                    store.update(op, "BLOCKED", "GITHUB_CHECKS_FAILED", null, op.evidence());
                } else if (message != null && (message.startsWith("UFD_PENDING:") || message.startsWith("CI_PENDING:"))) {
                    store.update(op, "WAITING_CI", "CI_PENDING", null, op.evidence());
                } else if (message != null && List.of("PR_OWNERSHIP_MISMATCH", "PR_CLOSED:",
                        "COMMIT_IDENTITY_INVALID", "CI_EVIDENCE_INCOMPLETE", "PR_READY_REJECTED", "MERGE_REJECTED",
                        "PR_EVIDENCE_INCOMPLETE").stream().anyMatch(message::startsWith)) {
                    store.update(op, "BLOCKED", "GITHUB_REJECTED", null, op.evidence());
                } else if (message != null && List.of("UFD_IDENTITY_REJECTED", "UFD_EVIDENCE_INCOMPLETE").contains(message)) {
                    store.update(op, "BLOCKED", message, null, op.evidence());
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

    private ReleaseRecoverySource requireRecoveryIdentity(DeliveryOperation plan, WorkSessionEntity session) {
        ReleaseRecoverySource proof = ReleaseRecoverySource.from(plan);
        if (proof == null) throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
        DeliveryOperation integrated = store.get(proof.integrationOperationId(), false);
        if (session.getAcceptanceState() != WorkSessionAcceptanceState.INTEGRATION_READY
                || session.getPullRequestStatus() != WorkSessionPullRequestStatus.MERGED
                || !"INTEGRATE".equals(integrated.kind()) || !"SUCCEEDED".equals(integrated.state())
                || integrated.target() != DeliveryTarget.APP_PROD || !integrated.sessionId().equals(session.getId())
                || !proof.publishedHeadCommit().equals(session.getFinalCommitSha())
                || !proof.publishedHeadCommit().equals(integrated.sourceCommit())
                || !proof.integratedMergeCommit().equals(integrated.evidence().path("mergeCommit").asText())) {
            throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
        }
        return proof;
    }

    private void requireRecoverySource(DeliveryOperation plan, WorkSessionEntity session) {
        ReleaseRecoverySource proof = requireRecoveryIdentity(plan, session);
        if (!proof.selectedMainCommit().equals(recoveryCanonicalMain())) {
            throw new DeliveryRejectedException("CANONICAL_MAIN_MOVED");
        }
        requireRecoveryAncestry(session, proof);
    }

    private String recoveryCanonicalMain() {
        try { return github.canonicalMain(APP); }
        catch (GitHubIntegrationException unavailable) { throw new DeliveryRejectedException("GITHUB_UNAVAILABLE"); }
    }

    private void requireCachedAncestry(WorkSessionEntity session, ReleaseRecoverySource proof) {
        String key=session.getId()+"|"+session.getPullRequestUrl()+"|"+session.getPublishedHeadBranch()+"|"+proof;
        synchronized (observedAncestry) {
            long now=Instant.now().getEpochSecond();
            if (observedAncestry.getOrDefault(key,0L)<=now) {
                requireRecoveryAncestry(session,proof);
                if (observedAncestry.size()>=128) observedAncestry.remove(observedAncestry.keySet().iterator().next());
                observedAncestry.put(key,now+60);
            }
        }
    }
    private void requireRecoveryAncestry(WorkSessionEntity session, ReleaseRecoverySource proof) {
        try {
            github.requireIntegratedChangeInSource(APP, github.extractPullRequestNumber(session.getPullRequestUrl()),
                    session.getPublishedHeadBranch(), proof.publishedHeadCommit(), proof.integratedMergeCommit(), proof.selectedMainCommit());
        } catch (GitHubIntegrationException rejected) {
            String code = rejected.getMessage();
            throw new DeliveryRejectedException(List.of("RELEASE_CHANGE_NOT_INCLUDED", "RELEASE_INTEGRATION_EVIDENCE_MISMATCH",
                    "RELEASE_ANCESTRY_EVIDENCE_INCOMPLETE", "PR_OWNERSHIP_MISMATCH", "COMMIT_IDENTITY_INVALID").contains(code)
                    ? code : "GITHUB_UNAVAILABLE");
        }
    }
    private void owned(WorkSessionEntity session) {
        try {
            if (session.getAcceptanceState() == WorkSessionAcceptanceState.INTEGRATION_READY) {
                ownership.requireExactIntegratedOwner(session);
            } else {
                ownership.requireExactOwner(session);
            }
        }
        catch (com.atenea.service.worksession.WorkSessionPublishConflictException exception) {
            throw new DeliveryRejectedException("VALIDATED_OWNERSHIP_REQUIRED");
        }
    }
    private void observedDeliveredOwner(WorkSessionEntity session) {
        try { ownership.requireExactDeliveredOwner(session); }
        catch (com.atenea.service.worksession.WorkSessionPublishConflictException exception) {
            throw new DeliveryRejectedException("VALIDATED_OWNERSHIP_REQUIRED");
        }
    }
    private void published(WorkSessionEntity session) {
        publishedHead(session);
        if (session.getPullRequestUrl() == null) throw new DeliveryRejectedException("PUBLISHED_OWNERSHIP_MISMATCH");
    }
    private void publishedHead(WorkSessionEntity session) {
        var change = session.getDevelopmentChange();
        if (change == null || !change.getChangeKey().equals(session.getPublishedChangeKey())
                || !Objects.equals(change.getSourceRevision(), session.getPublishedSourceRevision())
                || !Objects.equals(change.getSourceFingerprintSha256(), session.getPublishedSourceFingerprintSha256())
                || !Objects.equals(session.getSourceTreeFingerprintSha256(), session.getPublishedSourceFingerprintSha256())
                || !"jlnieto/atenea".equals(session.getPublishedRepository())
                || !"main".equals(session.getPublishedBaseBranch())
                || !Objects.equals(session.getWorkspaceBranch(), session.getPublishedHeadBranch())
                || session.getFinalCommitSha() == null
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
    PrivilegedActionBinding binding(DeliveryOperation op) {
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
