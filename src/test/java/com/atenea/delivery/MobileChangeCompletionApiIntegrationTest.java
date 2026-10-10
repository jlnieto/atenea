package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.*;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.*;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.*;
import com.atenea.service.worksession.*;
import com.atenea.service.developmentchange.DevelopmentChangeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"atenea.auth.bootstrap.enabled=false", "atenea.remote-worker.remote-close-release-enabled=true",
        "atenea.remote-worker.remote-close-project-allowlist[0]=atenea"})
@AutoConfigureMockMvc
@Transactional
class MobileChangeCompletionApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OperatorRepository operators;
    @Autowired ProjectRepository projects;
    @Autowired WorkSessionRepository sessions;
    @Autowired DevelopmentChangeRepository changes;
    @Autowired MobileChangeCompletionService completion;
    @Autowired DevelopmentChangeService changeService;
    @Autowired DeliveryStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean MobileDeliveryService delivery;
    @MockBean DevelopmentChangeBranchPublicationService ownership;
    @MockBean DevelopmentChangeWorkspaceGateway workspaces;
    @MockBean WorkSessionService close;
    OperatorEntity admin, routine;
    WorkSessionEntity session;
    DevelopmentChangeEntity change;

    @BeforeEach void fixture() {
        when(delivery.isEnabled()).thenReturn(true);
        admin=operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        routine=operator(CodexOperationsRole.ROUTINE_OPERATOR);
        Instant now=Instant.now();
        jdbc.update("""
                INSERT INTO worker_node(id,protocol_version,endpoint,enabled,healthy,normal_capacity,heavy_capacity,capabilities)
                VALUES ('ax42-01','agent-run-worker/v1','http://synthetic.invalid',true,true,1,1,'project-codex-v4')
                ON CONFLICT(id) DO NOTHING
                """);
        ProjectEntity project=new ProjectEntity(); project.setName("Completion "+UUID.randomUUID());
        project.setRepoPath("/synthetic"); project.setDefaultBaseBranch("main");
        project.setCreatedAt(now); project.setUpdatedAt(now); projects.saveAndFlush(project);
        UUID key=UUID.randomUUID();
        change=new DevelopmentChangeEntity(); change.setChangeKey(key); change.setProject(project); change.setTitle("Ephemeral completion test");
        change.setBaseRef("refs/heads/main"); change.setBaseCommit("1".repeat(40)); change.setObservedCanonicalCommit("2".repeat(40));
        change.setWorkspaceBranch("atenea/change-"+key); change.setWorkspaceIdentity("remote:ax42-01:change:"+key);
        change.setSelectedWorkerId("ax42-01"); change.setSourceRevision(5L); change.setSourceFingerprintSha256("5".repeat(64));
        change.setProjectPolicyRevision(1L);
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY); change.setValidationState(DevelopmentChangeProjectionState.CURRENT);
        change.setWorkspaceOperationRevision(1L); change.setWorkspaceObservationSha256("c".repeat(64)); change.setWorkspaceUpdatedAt(now);
        change.setSourceState(DevelopmentChangeSourceState.DIRTY); change.setCreatedAt(now); change.setUpdatedAt(now); changes.saveAndFlush(change);
        session=new WorkSessionEntity(); session.setProject(project); session.setDevelopmentChange(change); session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Ephemeral completion"); session.setBaseBranch("main"); session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setWorkspaceIdentity(change.getWorkspaceIdentity()); session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01"); session.setRemoteSessionId(UUID.randomUUID()); session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setRemoteCloseState(RemoteCloseState.NOT_STARTED); session.setPublishedChangeKey(key); session.setPublishedSourceRevision(5L);
        session.setPublishedSourceFingerprintSha256("5".repeat(64)); session.setSourceTreeFingerprintSha256("5".repeat(64));
        session.setSourceTreeObservedAt(now); session.setPublishedRepository("jlnieto/atenea"); session.setPublishedBaseBranch("main");
        session.setPublishedHeadBranch(change.getWorkspaceBranch()); session.setPublicationReceiptSha256("6".repeat(64));
        session.setFinalCommitSha("3".repeat(40)); session.setPullRequestStatus(WorkSessionPullRequestStatus.MERGED);
        session.setPullRequestUrl("https://github.com/jlnieto/atenea/pull/47"); session.setAcceptanceState(WorkSessionAcceptanceState.INTEGRATION_READY);
        session.setValidationProjectionSha256("7".repeat(64)); session.setValidationDefinitionRevision("test-v1");
        session.setValidatedAt(now); session.setIntegrationReadyAt(now); session.setOpenedAt(now); session.setLastActivityAt(now);
        session.setCreatedAt(now); session.setUpdatedAt(now); sessions.saveAndFlush(session);
        store.create(session.getId(),admin.getId(),"INTEGRATE",DeliveryTarget.APP_PROD,session.getFinalCommitSha(),"SUCCEEDED",
                mapper.createObjectNode().put("mergeCommit","4".repeat(40)));
        when(delivery.observeDeployment(eq(session.getId()),any())).thenReturn(new MobileDeliveryService.DeploymentObservation(
                session.getId(),"DEPLOYED","OPERATOR","8".repeat(40),"4".repeat(40),true,now.getEpochSecond(),
                UUID.randomUUID(),UUID.randomUUID(),"9".repeat(64),null));
        when(workspaces.execute(any())).thenReturn(new DevelopmentChangeWorkspaceObservation(
                DevelopmentChangeWorkspaceObservation.Disposition.OWNED,session.getFinalCommitSha(),null,false,false,"a".repeat(64)));
        when(close.closeSession(session.getId())).thenAnswer(call -> {
            session.setRemoteCloseState(RemoteCloseState.REQUESTED); session.setRemoteCloseOperationId(UUID.randomUUID());
            session.setRemoteCloseRevision(1); session.setRemoteCloseRequestedAt(Instant.now()); session.setRemoteCloseUpdatedAt(Instant.now());
            sessions.saveAndFlush(session);
            session.setStatus(WorkSessionStatus.CLOSED); session.setRemoteCloseState(RemoteCloseState.RELEASED); session.setRemoteCloseRevision(6);
            session.setRemoteCloseReceiptSha256("b".repeat(64));
            session.setRemoteCloseUpdatedAt(Instant.now()); session.setRemoteCloseReleasedAt(Instant.now()); session.setClosedAt(Instant.now());
            sessions.saveAndFlush(session); return null;
        });
    }
    private OperatorEntity operator(CodexOperationsRole role) {
        OperatorEntity value=new OperatorEntity(); value.setEmail(UUID.randomUUID()+"@atenea.test"); value.setDisplayName("Test");
        value.setPasswordHash("synthetic"); value.setActive(true); value.setCodexOperationsRole(role);
        value.setCreatedAt(Instant.now()); value.setUpdatedAt(Instant.now()); return operators.saveAndFlush(value);
    }
    private RequestPostProcessor auth(OperatorEntity actor) {
        var token=new UsernamePasswordAuthenticationToken(new AuthenticatedOperator(actor.getId(),actor.getEmail(),"Test"),null,
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        token.setDetails(UUID.randomUUID()); return authentication(token);
    }
    private UUID request() throws Exception {
        var response=mvc.perform(post("/api/mobile/sessions/{id}/delivery/finalize",session.getId()).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("FINALIZE"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(response).path("id").asText());
    }
    @Test void doubleTapAndLostHttpResponseRetainOneDurableCompletion() throws Exception {
        UUID id=request(); assertEquals(id,request());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation WHERE kind='FINALIZE' AND session_id=?",Integer.class,session.getId()));
        assertEquals(WorkSessionStatus.CLOSING,sessions.findById(session.getId()).orElseThrow().getStatus());
        assertTrue(store.pending().stream().noneMatch(id::equals)); assertTrue(store.pendingCompletions().contains(id));
        assertTrue(store.get(id,false).evidence().has("completionBindingSha256")); verifyNoInteractions(close);
        verify(workspaces,times(1)).execute(any());
    }
    @Test void completionAndPreservedHistoryArePersistedAtomically() throws Exception {
        UUID id=request(); completion.advance(id);
        assertEquals("SUCCEEDED",store.get(id,false).state());
        assertEquals(DevelopmentChangeStatus.COMPLETED,changes.findById(change.getId()).orElseThrow().getStatus());
        assertEquals(WorkSessionStatus.CLOSED,sessions.findById(session.getId()).orElseThrow().getStatus());
        assertEquals(session.getId(),store.completedSession(change.getChangeKey()));
        var detail=changeService.detail(session.getProject().getId(),change.getChangeKey());
        assertNull(detail.activeSessionId()); assertEquals(session.getId(),detail.completedSessionId());
        assertEquals(id,request()); verify(close,times(1)).closeSession(session.getId());
        assertEquals(2,store.list(session.getId()).size()); // Integration + completion; no new publication.
    }
    @Test void roleAndClosedProtocolRejectAdditionalAuthority() throws Exception {
        mvc.perform(post("/api/mobile/sessions/{id}/delivery/finalize",session.getId()).with(auth(routine))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        for (String field:List.of("path","command","workerId","changeKey","sourceCommit","force")) {
            mvc.perform(post("/api/mobile/sessions/{id}/delivery/finalize",session.getId()).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\""+field+"\":\"foreign\"}")).andExpect(status().isConflict());
        }
        assertTrue(store.completion(session.getId()).isEmpty()); verifyNoInteractions(close,workspaces);
    }
    @Test void activeValidationPreventsIntentAndClosing() throws Exception {
        insertValidation();
        mvc.perform(post("/api/mobile/sessions/{id}/delivery/finalize",session.getId()).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
        assertTrue(store.completion(session.getId()).isEmpty()); assertEquals(WorkSessionStatus.OPEN,session.getStatus());
    }
    @Test void databaseAdmissionRejectsValidationAfterCompletionIntent() throws Exception {
        request();
        assertThrows(org.springframework.dao.DataAccessException.class,this::insertValidation);
    }
    @Test void databaseAdmissionRejectsNewAgentRunsForTheSameClosingSession() throws Exception {
        Long turn=jdbc.queryForObject("INSERT INTO session_turn(session_id,actor,message_text) VALUES (?,'OPERATOR','Synthetic test') RETURNING id",
                Long.class,session.getId());
        request();
        var rejection=assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("""
                INSERT INTO agent_run(session_id,origin_turn_id,status,target_repo_path,started_at)
                VALUES (?,?,'RUNNING','/synthetic',now())
                """,session.getId(),turn));
        assertTrue(rejection.getMessage().contains("ATENEA_CHANGE_COMPLETION_IN_PROGRESS"));
    }
    @Test void databaseUniquenessAlsoRetainsTerminalCompletionIdentity() throws Exception {
        UUID id=request(); completion.advance(id);
        assertThrows(org.springframework.dao.DataAccessException.class,() -> store.create(session.getId(),admin.getId(),"FINALIZE",
                DeliveryTarget.APP_PROD,session.getFinalCommitSha(),"QUEUED",store.get(id,false).evidence()));
    }
    @Test void completionBindingCannotBeReplacedDuringReconciliation() throws Exception {
        UUID id=request(); DeliveryOperation op=store.get(id,false);
        var foreign=op.evidence().deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)foreign.path("completion")).put("workerId","foreign");
        assertEquals("COMPLETION_EVIDENCE_MISMATCH",assertThrows(DeliveryRejectedException.class,
                () -> store.update(op,"APPLYING",null,null,foreign)).code());
    }
    private void insertValidation() {
        jdbc.update("""
                INSERT INTO validation_operation(id,work_session_id,operation,status,source_tree_fingerprint_sha256,
                    definition_revision,identity_sha256,started_at,created_at,updated_at)
                VALUES (?,?,'BACKEND_TEST','RUNNING',?,'test-v1',?,now(),now(),now())
                """,UUID.randomUUID(),session.getId(),"5".repeat(64),UUID.randomUUID().toString().replace("-","").repeat(2));
    }
}
