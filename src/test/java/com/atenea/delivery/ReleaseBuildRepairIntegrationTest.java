package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.github.*;
import com.atenea.persistence.auth.*;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.*;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.*;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateCommand.Action;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateGateway.Preparation;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateGateway.State;
import com.atenea.service.worksession.*;
import com.atenea.service.developmentchange.DevelopmentChangeAgentRunSourceAdvanceService;
import com.atenea.support.ChangeOwnedSessionFixtures;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Entire database and all remotes are ephemeral; no real operation is executed. */
@SpringBootTest(properties={"atenea.auth.bootstrap.enabled=false","atenea.remote-worker.enabled=true"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReleaseBuildRepairIntegrationTest {
    static final String DATABASE="atenea_test_repair_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry properties) {
        String url=System.getenv("SPRING_DATASOURCE_URL");
        if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/atenea_test")) throw new IllegalStateException("Ephemeral PG16 required");
        try(var db=java.sql.DriverManager.getConnection(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));var s=db.createStatement()) {
            s.execute("CREATE DATABASE \""+DATABASE+"\"");
        } catch(java.sql.SQLException failed) { throw new IllegalStateException("Ephemeral repair database unavailable",failed); }
        properties.add("spring.datasource.url",()->url.substring(0,url.length()-"atenea_test".length())+DATABASE);
        properties.add("spring.flyway.schemas",()->"public");properties.add("spring.flyway.default-schema",()->"public");
    }
    @AfterAll static void removeEphemeralSchema() throws Exception {
        try(var db=java.sql.DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));var s=db.createStatement()) {
            s.execute("DROP DATABASE \""+DATABASE+"\" WITH (FORCE)");
        }
    }
    @Autowired SourceUpdateService service;@Autowired SourceUpdateStore updates;@Autowired ReleaseRepairStore repairs;
    @Autowired MobileDeliveryService delivery;@Autowired DeliveryStore deliveries;@Autowired JdbcTemplate jdbc;
    @Autowired WorkSessionRepository sessions;@Autowired DevelopmentChangeRepository changes;
    @Autowired ProjectRepository projects;@Autowired OperatorRepository operators;@Autowired AgentRunRepository runs;
    @Autowired PlatformTransactionManager manager;@Autowired DevelopmentChangeAgentRunSourceAdvanceService advance;
    @Autowired WorkSessionGitHubService publication;
    @MockBean GitHubClient github;@MockBean ReleaseControlClient executor;
    @MockBean RemoteRoutingSelector routing;@MockBean RemoteWorkerClient worker;@MockBean RemoteAgentRunCoordinator coordinator;
    @MockBean DevelopmentChangeSourceUpdateGateway gateway;
    @MockBean DevelopmentChangeSourceFinalizationGateway finalizer;
    @MockBean com.atenea.codexoperations.CodexExecutionProfileSnapshotService profiles;
    @MockBean(name="mobileDeliveryScheduler") ThreadPoolTaskScheduler scheduler;
    final String base="1".repeat(40),head="2".repeat(40),main="3".repeat(40),fp="4".repeat(64),prepared="5".repeat(64),result="6".repeat(64);

    @Test void recoverySurvivesObservationAndUsesOneRunThenOneLinkedRepairPrWithoutReopeningTheOriginal() {
        var tx=new TransactionTemplate(manager);var now=Instant.now();
        when(executor.enabled()).thenReturn(true);when(routing.refreshKnownWorker(eq("ax42-01"),anyString())).thenReturn(true);
        when(github.canonicalMain(any())).thenReturn(main);when(github.extractPullRequestNumber(anyString())).thenReturn(80L);
        when(github.resolveRepository(ProjectCodexIdentity.REPOSITORY)).thenReturn(new GitHubRepositoryRef("jlnieto","atenea"));
        when(github.observeMergeState(any(),anyLong(),anyString(),anyString())).thenReturn(GitHubMergeState.MERGED);
        var session=tx.execute(ignored->{
            jdbc.update("INSERT INTO worker_node(id,protocol_version,endpoint,enabled,healthy,normal_capacity,heavy_capacity,capabilities) VALUES('ax42-01','agent-run-worker/v1','http://synthetic.invalid',true,true,1,1,'project-codex-v4')");
            var project=new ProjectEntity();project.setName("Atenea");project.setRepoPath("/synthetic/atenea");project.setDefaultBaseBranch("main");
            project.setCreatedAt(now);project.setUpdatedAt(now);projects.saveAndFlush(project);
            var s=new WorkSessionEntity();s.setProject(project);s.setTitle("Synthetic post-merge repair");s.setBaseBranch("main");
            s.setStatus(WorkSessionStatus.OPEN);s.setExecutionTarget(ExecutionTarget.REMOTE);s.setSelectedWorkerId("ax42-01");
            s.setCanonicalSourceCommit(base);s.setCanonicalSourceRef("refs/heads/main");s.setCanonicalSourceObservationSha256("a".repeat(64));
            s.setCanonicalSourceObservedAt(now);s.setRemoteSessionId(UUID.randomUUID());s.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
            s.setRemoteCloseState(RemoteCloseState.NOT_STARTED);s.setCreatedAt(now);s.setUpdatedAt(now);s.setOpenedAt(now);s.setLastActivityAt(now);
            ChangeOwnedSessionFixtures.bind(s,changes);var c=s.getDevelopmentChange();c.setSourceRevision(2);c.setSourceFingerprintSha256(fp);
            c.setSourceState(DevelopmentChangeSourceState.DIRTY);c.setValidationState(DevelopmentChangeProjectionState.CURRENT);changes.saveAndFlush(c);
            s.setAcceptanceState(WorkSessionAcceptanceState.INTEGRATION_READY);s.setIntegrationReadyAt(now);s.setValidatedAt(now);
            s.setSourceTreeFingerprintSha256(fp);s.setSourceTreeObservedAt(now);s.setValidationProjectionSha256("b".repeat(64));
            s.setValidationDefinitionRevision("atenea-required-validation-v2");s.setFinalCommitSha(head);
            s.setPublishedChangeKey(c.getChangeKey());s.setPublishedSourceRevision(2L);s.setPublishedSourceFingerprintSha256(fp);
            s.setPublishedWorkspaceOwnershipFingerprintSha256(fp);s.setPublicationReceiptSha256("c".repeat(64));
            s.setPublishedRepository("jlnieto/atenea");s.setPublishedHeadBranch(c.getWorkspaceBranch());s.setPublishedBaseBranch("main");
            s.setPullRequestUrl("https://github.com/jlnieto/atenea/pull/80");s.setPullRequestStatus(WorkSessionPullRequestStatus.MERGED);
            return sessions.saveAndFlush(s);
        });
        var actor=tx.execute(ignored->{var o=new OperatorEntity();o.setEmail("synthetic@release.test");o.setDisplayName("Synthetic");
            o.setPasswordHash("synthetic");o.setActive(true);o.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
            o.setCreatedAt(now);o.setUpdatedAt(now);operators.saveAndFlush(o);return new AuthenticatedOperator(o.getId(),o.getEmail(),o.getDisplayName());});
        var failed=tx.execute(ignored->{
            deliveries.create(session.getId(),actor.operatorId(),"INTEGRATE",DeliveryTarget.APP_PROD,head,"SUCCEEDED",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("mergeCommit",main));
            var op=deliveries.create(session.getId(),actor.operatorId(),"RELEASE",DeliveryTarget.APP_PROD,main,"BLOCKED",new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
            deliveries.update(op,"BLOCKED","RELEASE_BUILD_FAILED",null,op.evidence());return deliveries.get(op.id(),false);});
        var proof=new GitHubReleaseBuild.Evidence(main,"FAILED",91L,1,"https://github.com/jlnieto/atenea/actions/runs/91","BACKEND_COMPILATION",null,
            List.of(new GitHubReleaseBuild.Failure("COMPILATION","src/test/java/com/atenea/Test.java",71,null,null)));
        var request=new SourceUpdateService.BuildRepair(failed,proof,main,List.of(DeliveryTarget.APP_PROD,DeliveryTarget.ANDROID_STABLE));
        when(github.canonicalMain(any())).thenReturn("b".repeat(40));
        assertEquals("RELEASE_REPAIR_SOURCE_MOVED",assertThrows(DeliveryRejectedException.class,
            ()->service.requestRepair(session.getId(),actor,request)).code());
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM mobile_release_build_repair",Long.class));
        when(github.canonicalMain(any())).thenReturn(main);
        var admitted=service.requestRepair(session.getId(),actor,request);
        assertEquals(admitted.id(),service.requestRepair(session.getId(),actor,request).id());
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM mobile_release_build_repair",Long.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE mobile_release_build_repair SET original_head=?",base));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM mobile_release_build_repair"));
        var command=updates.get(admitted.id(),false).command();
        when(gateway.exchange(command,Action.PREPARE)).thenReturn(new Preparation(State.READY_TO_FINALIZE,"7".repeat(40),List.of(),null,"8".repeat(64)));
        when(worker.fingerprintSourceTree(any())).thenAnswer(call->{var s=call.getArgument(0,WorkSessionEntity.class);
            return new RemoteWorkerClient.SourceTreeFingerprint("observed",s.getRemoteSessionId().toString(),s.getWorkspaceIdentity(),"atenea",head,prepared,0,0,0,false);});
        service.reconcile(admitted.id());assertEquals("READY_TO_RESOLVE",updates.get(admitted.id(),false).state());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE mobile_release_build_repair SET prepared_fingerprint=?",fp));
        assertEquals("BUILD_REPAIR",service.observe(session.getId(),actor).purpose());
        service.reconcile(admitted.id());var resolving=updates.get(admitted.id(),false);
        assertEquals("RESOLVING",resolving.state());assertNotNull(resolving.resolverRunId());
        service.observe(session.getId(),actor);service.observe(session.getId(),actor);
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM agent_run",Long.class));
        verify(coordinator,times(1)).dispatchAfterCommit(resolving.resolverRunId());
        assertTrue(jdbc.queryForObject("SELECT message_text FROM session_turn WHERE id=?",String.class,resolving.resolverTurnId()).contains("Test.java"));
        tx.executeWithoutResult(ignored->{var run=runs.findById(resolving.resolverRunId()).orElseThrow();run.setRemoteExecutionId(UUID.randomUUID().toString());
            advance.advance(run,new RemoteWorkerClient.SourceIdentity(run.getDevelopmentChangeKey().toString(),session.getId(),
                run.getRemoteSessionId().toString(),run.getWorkspaceIdentity(),run.getRemoteExecutionId(),head,result,true),Instant.now());
            run.setStatus(AgentRunStatus.SUCCEEDED);run.setProcessOutcome(AgentRunProcessOutcome.SUCCEEDED);run.setFinishedAt(Instant.now());runs.saveAndFlush(run);});
        jdbc.update("UPDATE mobile_source_update_operation SET lease_until=now()-interval '1 second' WHERE id=?",admitted.id());
        service.reconcile(admitted.id());assertEquals("RESOLVER_COMPLETED",updates.get(admitted.id(),false).state());
        // Simulated test results are synthetic fixtures, never claimed as a real validation.
        tx.executeWithoutResult(ignored->{var s=sessions.findWithProjectAndDevelopmentChangeById(session.getId()).orElseThrow();
            s.setAcceptanceState(WorkSessionAcceptanceState.VALIDATED);s.setSourceTreeFingerprintSha256(result);s.setValidationProjectionSha256("d".repeat(64));
            s.setValidationDefinitionRevision("atenea-required-validation-v2");s.setAcceptanceBlockedCheck(null);s.setIntegrationReadyAt(null);
            s.setValidatedAt(now);s.setSourceTreeObservedAt(now);s.getDevelopmentChange().setValidationState(DevelopmentChangeProjectionState.CURRENT);
            sessions.saveAndFlush(s);
            for(var kind:ValidationOperationKind.values()) jdbc.update("""
                INSERT INTO validation_operation(id,work_session_id,operation,status,source_tree_fingerprint_sha256,
                  definition_revision,identity_sha256,exit_code,duration_millis,started_at,finished_at,created_at,updated_at)
                VALUES(?,?,?,'SUCCEEDED',?,?,?,0,1,now(),now(),now(),now())
                """,UUID.randomUUID(),s.getId(),kind.name(),result,kind.definitionRevision(),UUID.randomUUID().toString().replace("-","").repeat(2));
        });
        tx.executeWithoutResult(ignored->deliveries.create(session.getId(),actor.operatorId(),"PUBLISH_PR",DeliveryTarget.APP_PROD,null,"QUEUED",new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()));
        var absent=new DevelopmentChangeSourceFinalizationGateway.Result(DevelopmentChangeSourceFinalizationGateway.FinalizationState.ABSENT,null,null,null);
        var published=new DevelopmentChangeSourceFinalizationGateway.Result(DevelopmentChangeSourceFinalizationGateway.FinalizationState.PUBLISHED,"9".repeat(40),"a".repeat(40),"f".repeat(64));
        when(finalizer.finalizeSource(any(),eq(DevelopmentChangeSourceFinalizationCommand.Action.INSPECT))).thenReturn(absent);
        when(finalizer.finalizeSource(any(),eq(DevelopmentChangeSourceFinalizationCommand.Action.FINALIZE))).thenReturn(published);
        when(github.findOpenPullRequests(any(),any(),any())).thenReturn(List.of());
        when(github.createPullRequest(any(),any(),any(),any(),any())).thenReturn(new GitHubPullRequest(81,"https://github.com/jlnieto/atenea/pull/81","open",false,
            "jlnieto/atenea","main","jlnieto/atenea",session.getWorkspaceBranch(),"9".repeat(40),true));
        publication.publishForDelivery(session.getId());
        assertEquals("https://github.com/jlnieto/atenea/pull/81",jdbc.queryForObject("SELECT pull_request_url FROM work_session WHERE id=?",String.class,session.getId()));
        var binding=repairs.failed(failed.id());assertEquals("https://github.com/jlnieto/atenea/pull/80",binding.originalPrUrl());
        assertEquals("https://github.com/jlnieto/atenea/pull/81",binding.repairPrUrl());
        assertEquals(List.of(DeliveryTarget.APP_PROD,DeliveryTarget.ANDROID_STABLE),repairs.retainedTargets(session.getId()));
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM work_session",Long.class));
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM development_change",Long.class));
        verify(executor,never()).execute(any(),any(),any());
    }
}
