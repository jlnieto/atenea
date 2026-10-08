package com.atenea.delivery;

import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubMergeState;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionAcceptanceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.remoteworker.DevelopmentChangeBranchPublicationCommand;
import com.atenea.remoteworker.DevelopmentChangeSourceFinalizationCommand;
import com.atenea.remoteworker.DevelopmentChangeSourceFinalizationCommand.Action;
import com.atenea.remoteworker.DevelopmentChangeSourceFinalizationGateway;
import com.atenea.remoteworker.DevelopmentChangeSourceFinalizationGateway.FinalizationState;
import com.atenea.remoteworker.DevelopmentChangeSourceFinalizationGateway.Result;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteRoutingSelector;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.service.worksession.DevelopmentChangeBranchPublicationService.PublishedIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Extends one sealed publication, after new validation, before same-PR UFD. */
@Service
public class SourceFinalizationService {
    private static final GitHubRepositoryRef APP = new GitHubRepositoryRef("jlnieto", "atenea");
    private final SourceUpdateStore updates;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WorkSessionRepository sessions;
    private final OperatorRepository operators;
    private final DevelopmentChangeSourceFinalizationGateway gateway;
    private final RemoteRoutingSelector routing;
    private final RemoteWorkerProperties worker;
    private final GitHubClient github;
    private final TransactionTemplate transaction;
    public SourceFinalizationService(SourceUpdateStore updates, JdbcTemplate jdbc, ObjectMapper mapper,
            WorkSessionRepository sessions, OperatorRepository operators, DevelopmentChangeSourceFinalizationGateway gateway,
            RemoteRoutingSelector routing, RemoteWorkerProperties worker, GitHubClient github, PlatformTransactionManager manager) {
        this.updates=updates; this.jdbc=jdbc; this.mapper=mapper; this.sessions=sessions; this.operators=operators;
        this.gateway=gateway; this.routing=routing; this.worker=worker; this.github=github;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<PublishedIdentity> publish(Long sessionId, DevelopmentChangeBranchPublicationCommand validated) {
        var intent = transaction.execute(ignored -> prepare(sessionId, validated));
        if (intent == null) return Optional.empty();
        if (intent.result() != null) return Optional.of(identity(intent.command(), intent.result()));
        try {
            Result result = gateway.finalizeSource(intent.command(), Action.INSPECT);
            if (result.state() != FinalizationState.PUBLISHED) result = gateway.finalizeSource(intent.command(), Action.FINALIZE);
            Result confirmed = result;
            return Optional.of(Objects.requireNonNull(transaction.execute(ignored -> persist(intent, confirmed))));
        } catch (RuntimeException failure) {
            boolean rejected = failure instanceof com.atenea.remoteworker.RemoteWorkerException remote
                && remote.getCategory()!=null && remote.getCategory()!=com.atenea.remoteworker.RemoteWorkerFailureCategory.TRANSPORT;
            transaction.executeWithoutResult(ignored -> jdbc.update("""
                    UPDATE mobile_source_finalization SET state=?,lease_until=now(),updated_at=now()
                    WHERE id=? AND result_json IS NULL
                    """, rejected ? "ATTENTION" : "UNCERTAIN",intent.command().owner().operationId()));
            if (rejected) throw reject("SOURCE_FINALIZATION_REJECTED");
            throw failure;
        }
    }

    private Intent prepare(Long sessionId, DevelopmentChangeBranchPublicationCommand validated) {
        var session = sessions.findLockedWithProjectAndDevelopmentChangeById(sessionId).orElseThrow();
        var update = updates.latest(sessionId).orElse(null);
        if (update == null) return null;
        if (!java.util.Set.of("RESOLVER_COMPLETED","READY_TO_FINALIZE","PUBLISHED").contains(update.state())) throw reject("SOURCE_FINALIZATION_NOT_READY");
        var retained = jdbc.query("SELECT command_json,result_json,predecessor_json FROM mobile_source_finalization WHERE preparation_id=? FOR UPDATE",
            (rs,index) -> new Intent(read(rs.getString(1), DevelopmentChangeSourceFinalizationCommand.class),
                rs.getString(2)==null ? null : read(rs.getString(2), Result.class), read(rs.getString(3), Predecessor.class)), update.id());
        Intent intent;
        if (!retained.isEmpty()) {
            intent=retained.getFirst();
            requireSnapshot(session, intent.command(), validated);
            if (intent.result() != null) {
                if (!Objects.equals(session.getFinalCommitSha(), intent.result().publishedHeadSha())
                        || !Objects.equals(session.getPublicationReceiptSha256(), intent.result().finalizationReceiptSha256())
                        || !Objects.equals(session.getPublishedSourceRevision(), validated.sourceRevision())) throw reject("SOURCE_FINALIZATION_PUBLISHED_IDENTITY_MOVED");
                return intent;
            }
        } else {
            var source = update.command();
            if (!Objects.equals(session.getFinalCommitSha(), source.owner().sourceCommit())
                    || !Objects.equals(session.getPublicationReceiptSha256(), source.publicationReceiptSha256())
                    || !Objects.equals(validated.sourceCommit(), source.owner().sourceCommit())
                    || update.preparation()==null || validated.sourceRevision() <= source.owner().sourceRevision()
                    || !Objects.equals(validated.sourceRevision(), update.resultRevision()==null ? update.preparedRevision() : update.resultRevision())
                    || !Objects.equals(validated.sourceFingerprintSha256(), update.resultRevision()==null
                        ? update.preparation().preparedFingerprintSha256() : update.resultFingerprintSha256())) throw reject("SOURCE_FINALIZATION_EVIDENCE_MISMATCH");
            var authorizations = jdbc.query("""
                SELECT id,operator_id FROM mobile_delivery_operation WHERE session_id=? AND kind='PUBLISH_PR' AND target='APP_PROD'
                    AND state IN ('QUEUED','WAITING_CI') ORDER BY created_at DESC LIMIT 1
                """, (rs,index) -> new Authorization(rs.getObject(1,UUID.class),rs.getLong(2)), sessionId);
            if (authorizations.isEmpty()) throw reject("SOURCE_FINALIZATION_AUTHORIZATION_REQUIRED");
            var authorization=authorizations.getFirst();
            var actor=operators.findById(authorization.actor()).orElseThrow();
            if (!actor.isActive() || actor.getCodexOperationsRole()!=CodexOperationsRole.PLATFORM_ADMINISTRATOR) throw reject("PLATFORM_ADMINISTRATOR_REQUIRED");
            if (!routing.refreshKnownWorker(ProjectCodexIdentity.WORKER_ID, "development-change-source-finalization/v1")) throw reject("SOURCE_FINALIZATION_CAPABILITY_UNAVAILABLE");
            if (!Objects.equals(github.canonicalMain(APP), source.targetMainCommit())) throw reject("SOURCE_FINALIZATION_MAIN_MOVED");
            var merge=github.observeMergeState(APP, github.extractPullRequestNumber(session.getPullRequestUrl()),
                validated.workspaceBranch(), session.getFinalCommitSha());
            if (merge==null || merge==GitHubMergeState.MERGED || merge==GitHubMergeState.CLOSED) throw reject("SOURCE_FINALIZATION_SAME_OPEN_PR_REQUIRED");
            var command=new DevelopmentChangeSourceFinalizationCommand(validated, source.targetMainCommit(), source.publicationReceiptSha256(),
                update.id(), update.preparation().receiptSha256(), session.getValidationProjectionSha256());
            intent=new Intent(command,null,new Predecessor(session.getFinalCommitSha(),session.getPublishedSourceRevision(),
                session.getPublishedSourceFingerprintSha256(), session.getPublicationReceiptSha256(), session.getPullRequestUrl()));
            requireSnapshot(session, command, validated);
            requireIdle();
            jdbc.update("""
                INSERT INTO mobile_source_finalization (id,preparation_id,delivery_operation_id,session_id,state,command_json,predecessor_json)
                VALUES (?,?,?,?,'QUEUED',?::jsonb,?::jsonb)
                """, command.owner().operationId(),update.id(),authorization.operation(),sessionId,json(command),json(intent.predecessor()));
        }
        requireIdle();
        if (jdbc.update("""
            UPDATE mobile_source_finalization SET state='CLAIMED',lease_until=now()+(? * interval '1 second'),updated_at=now()
            WHERE id=? AND lease_until<=now() AND state IN ('QUEUED','CLAIMED','UNCERTAIN')
            """, worker.getWorkspaceProvisionTimeout().multipliedBy(2).plusSeconds(60).toSeconds(),intent.command().owner().operationId())!=1) {
            throw reject("SOURCE_FINALIZATION_IN_PROGRESS");
        }
        return intent;
    }

    private PublishedIdentity persist(Intent intent, Result result) {
        var command=intent.command();
        Long sessionId=jdbc.queryForObject("SELECT session_id FROM mobile_source_finalization WHERE id=?",Long.class,command.owner().operationId());
        var session=sessions.findLockedWithProjectAndDevelopmentChangeById(sessionId).orElseThrow();
        var change=session.getDevelopmentChange();
        requireSnapshot(session, command, command.owner());
        requireIdle();
        if (result==null || result.state()!=FinalizationState.PUBLISHED || result.publishedHeadSha()==null
                || !result.publishedHeadSha().matches("[0-9a-f]{40}") || result.expectedTreeSha()==null
                || !result.expectedTreeSha().matches("[0-9a-f]{40}") || result.finalizationReceiptSha256()==null
                || !result.finalizationReceiptSha256().matches("[0-9a-f]{64}")
                || !Objects.equals(session.getFinalCommitSha(), intent.predecessor().head())
                || !Objects.equals(session.getPublicationReceiptSha256(), intent.predecessor().receipt())
                || !Objects.equals(session.getPullRequestUrl(), intent.predecessor().pullRequestUrl())) throw reject("SOURCE_FINALIZATION_RECEIPT_MISMATCH");
        jdbc.update("UPDATE mobile_source_finalization SET state='PUBLISHED',result_json=?::jsonb,updated_at=now(),lease_until=now() WHERE id=?",
            json(result),command.owner().operationId());
        session.setFinalCommitSha(result.publishedHeadSha());
        session.setPublishedSourceRevision(command.owner().sourceRevision());
        session.setPublishedSourceFingerprintSha256(change.getSourceFingerprintSha256());
        session.setPublishedWorkspaceOwnershipFingerprintSha256(change.getSourceFingerprintSha256());
        session.setPublicationReceiptSha256(result.finalizationReceiptSha256());
        session.setUpdatedAt(Instant.now()); sessions.saveAndFlush(session);
        updates.state(command.preparationOperationId(),"PUBLISHED",null);
        return identity(command,result);
    }

    private void requireSnapshot(WorkSessionEntity session, DevelopmentChangeSourceFinalizationCommand command,
            DevelopmentChangeBranchPublicationCommand validated) {
        var change=session.getDevelopmentChange();
        if (!Objects.equals(command.owner(),validated)
                || session.getAcceptanceState()!=WorkSessionAcceptanceState.VALIDATED
                || change.getValidationState()!=DevelopmentChangeProjectionState.CURRENT
                || !Objects.equals(change.getChangeKey(),validated.changeKey())
                || change.getProject().getId()!=validated.databaseProjectId()
                || !Objects.equals(change.getBaseCommit(),validated.baseCommit())
                || !Objects.equals(change.getWorkspaceBranch(),validated.workspaceBranch())
                || !Objects.equals(change.getWorkspaceIdentity(),validated.workspaceIdentity())
                || !Objects.equals(change.getSelectedWorkerId(),validated.workerId())
                || !Objects.equals(session.getSelectedWorkerId(),validated.workerId())
                || !Objects.equals(session.getWorkspaceBranch(),validated.workspaceBranch())
                || !Objects.equals(session.getWorkspaceIdentity(),validated.workspaceIdentity())
                || !Objects.equals(session.getPublishedChangeKey(),validated.changeKey())
                || session.getStatus()!=com.atenea.persistence.worksession.WorkSessionStatus.OPEN
                || session.getRemoteCloseState()!=com.atenea.persistence.worksession.RemoteCloseState.NOT_STARTED
                || change.getStatus()!=com.atenea.persistence.developmentchange.DevelopmentChangeStatus.OPEN
                || change.getWorkspaceState()!=com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState.READY
                || (validated.sourceFingerprintSha256()!=null && !Objects.equals(change.getSourceFingerprintSha256(),validated.sourceFingerprintSha256()))
                || (validated.sourceFingerprintSha256()==null && change.getSourceState()!=com.atenea.persistence.developmentchange.DevelopmentChangeSourceState.CLEAN)
                || !Objects.equals(session.getValidationProjectionSha256(),command.validationProjectionSha256())
                || !Objects.equals(session.getDevelopmentChange().getSourceRevision(),validated.sourceRevision())
                || !Objects.equals(session.getDevelopmentChange().getObservedCanonicalCommit(),validated.sourceCommit())
                || !Objects.equals(session.getSourceTreeFingerprintSha256(),session.getDevelopmentChange().getSourceFingerprintSha256())) throw reject("SOURCE_FINALIZATION_EVIDENCE_MISMATCH");
        Integer passed=jdbc.queryForObject("""
            SELECT count(*) FROM (SELECT DISTINCT ON (operation) operation,status,exit_code,definition_revision
                FROM validation_operation WHERE work_session_id=? AND source_tree_fingerprint_sha256=?
                ORDER BY operation,started_at DESC,id DESC) v WHERE status='SUCCEEDED' AND exit_code=0
                  AND (operation='BACKEND_TEST' AND definition_revision='atenea-backend-test-v2'
                    OR operation='WEB_BUILD' AND definition_revision='atenea-web-build-v1'
                    OR operation='ANDROID_BUILD' AND definition_revision='atenea-android-build-v2'
                    OR operation='PLAYWRIGHT_ACCEPTANCE' AND definition_revision='atenea-playwright-acceptance-v1')
            """,Integer.class,session.getId(),session.getSourceTreeFingerprintSha256());
        if (passed==null || passed!=4) throw reject("SOURCE_FINALIZATION_CURRENT_VALIDATION_REQUIRED");
        Long operations=jdbc.queryForObject("SELECT count(*) FROM development_change_workspace_operation WHERE development_change_id=? AND state IN ('REQUESTED','DISPATCHED')",Long.class,change.getId());
        if (operations==null || operations!=0) throw reject("SOURCE_FINALIZATION_WORKSPACE_OPERATION_ACTIVE");
    }
    private void requireIdle() {
        jdbc.execute("SELECT pg_advisory_xact_lock(814205002)");
        Long active=jdbc.queryForObject("""
            SELECT (SELECT count(*) FROM agent_run WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED'))
                + (SELECT count(*) FROM validation_operation WHERE status='RUNNING')
                + (SELECT count(*) FROM mobile_delivery_operation WHERE kind='RELEASE'
                    AND state NOT IN ('SUCCEEDED','ROLLED_BACK','FAILED','BLOCKED','ROLLBACK_FAILED'))
            """,Long.class);
        if (active==null || active!=0) throw reject("SOURCE_FINALIZATION_EXECUTION_ACTIVE");
    }
    private PublishedIdentity identity(DevelopmentChangeSourceFinalizationCommand command, Result result) {
        var owner=command.owner();
        return new PublishedIdentity("jlnieto/atenea", owner.repositoryBranch(),owner.workspaceBranch(),result.publishedHeadSha(),
            owner.changeKey(),owner.sourceRevision(),owner.sourceFingerprintSha256(),result.finalizationReceiptSha256());
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value,type); } catch (java.io.IOException error) { throw new IllegalStateException("Invalid finalization evidence",error); }
    }
    private DeliveryRejectedException reject(String code) { return new DeliveryRejectedException(code); }
    private record Authorization(UUID operation,Long actor) { }
    private record Predecessor(String head,Long revision,String fingerprint,String receipt,String pullRequestUrl) { }
    private record Intent(DevelopmentChangeSourceFinalizationCommand command,Result result,Predecessor predecessor) { }
}
