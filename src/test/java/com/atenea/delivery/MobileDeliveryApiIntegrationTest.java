package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.action.PrivilegedActionAuthorizationService;
import com.atenea.auth.recovery.OperatorRecoveryService;
import com.atenea.github.GitHubClient;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.worksession.DevelopmentChangeBranchPublicationService;
import com.atenea.service.worksession.WorkSessionGitHubService;
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

@SpringBootTest(properties={"atenea.auth.bootstrap.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class MobileDeliveryApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OperatorRepository operators;
    @Autowired ProjectRepository projects;
    @Autowired WorkSessionRepository sessions;
    @Autowired DeliveryStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean ReleaseControlClient executor;
    @MockBean GitHubClient github;
    @MockBean DevelopmentChangeBranchPublicationService ownership;
    @MockBean WorkSessionGitHubService publication;
    @MockBean OperatorRecoveryService factors;
    @MockBean PrivilegedActionAuthorizationService grants;
    OperatorEntity admin, routine;
    Long sessionId;
    @BeforeEach void fixture() {
        when(executor.enabled()).thenReturn(true);
        admin=operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        routine=operator(CodexOperationsRole.ROUTINE_OPERATOR);
        ProjectEntity project=new ProjectEntity(); project.setName("Delivery API "+UUID.randomUUID());
        project.setRepoPath("/synthetic"); project.setDefaultBaseBranch("main");
        project.setCreatedAt(Instant.now()); project.setUpdatedAt(Instant.now()); projects.saveAndFlush(project);
        WorkSessionEntity session=new WorkSessionEntity(); session.setProject(project); session.setStatus(WorkSessionStatus.OPEN);
        session.setPullRequestStatus(com.atenea.persistence.worksession.WorkSessionPullRequestStatus.NOT_CREATED);
        session.setTitle("Isolated delivery API"); session.setBaseBranch("main"); session.setWorkspaceIdentity("local:test:"+UUID.randomUUID());
        session.setOpenedAt(Instant.now()); session.setLastActivityAt(Instant.now());
        session.setCreatedAt(Instant.now()); session.setUpdatedAt(Instant.now()); sessionId=sessions.saveAndFlush(session).getId();
    }
    private OperatorEntity operator(CodexOperationsRole role) {
        OperatorEntity value=new OperatorEntity(); value.setEmail(UUID.randomUUID()+"@atenea.test");
        value.setDisplayName("Test"); value.setPasswordHash("synthetic"); value.setActive(true);
        value.setCodexOperationsRole(role); value.setCreatedAt(Instant.now()); value.setUpdatedAt(Instant.now());
        return operators.saveAndFlush(value);
    }
    private RequestPostProcessor auth(OperatorEntity actor) {
        var token=new UsernamePasswordAuthenticationToken(new AuthenticatedOperator(actor.getId(),actor.getEmail(),"Test"),
                null,List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        token.setDetails(UUID.randomUUID()); return authentication(token);
    }
    @Test void effectiveRoutineRoleCannotQueueDelivery() throws Exception {
        mvc.perform(post("/api/mobile/sessions/{id}/delivery/pr",sessionId).with(auth(routine))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation",Integer.class));
    }
    @Test void unknownPathsCommandsShaAndHostsCannotEnterProtocol() throws Exception {
        for (String key:List.of("sourceCommit","command","path","host","token")) {
            mvc.perform(post("/api/mobile/sessions/{id}/delivery/pr",sessionId).with(auth(admin))
                    .contentType(MediaType.APPLICATION_JSON).content("{\""+key+"\":\"foreign\"}"))
                    .andExpect(status().isConflict());
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation",Integer.class));
    }
    @Test void doubleTapQueuesOneDurableIntentWithoutDispatchingFromHttpRequest() throws Exception {
        String first=mvc.perform(post("/api/mobile/sessions/{id}/delivery/pr",sessionId).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(first).path("id").asText();
        mvc.perform(post("/api/mobile/sessions/{id}/delivery/pr",sessionId).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation",Integer.class));
        verify(executor,never()).execute(any(),any(),any()); verifyNoInteractions(publication);
    }
    @Test void readOnlyPollingDoesNotConsumeFactorOrDispatch() throws Exception {
        var op=store.create(sessionId,admin.getId(),"RELEASE",DeliveryTarget.APP_PROD,"1".repeat(40),"PREPARING",mapper.createObjectNode());
        mvc.perform(get("/api/mobile/sessions/{id}/delivery",sessionId).with(auth(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.operations[0].id").value(op.id().toString()))
                .andExpect(jsonPath("$.operations[0].state").value("PREPARING"))
                .andExpect(jsonPath("$.operations[0].path").doesNotExist()).andExpect(jsonPath("$.operations[0].token").doesNotExist());
        verifyNoInteractions(factors,grants);
        verify(executor,never()).inspect(any());
    }
    @Test void unpreparedPlanCannotConsumeTotpAndUnknownArgumentsAreRejected() throws Exception {
        var op=store.create(sessionId,admin.getId(),"RELEASE",DeliveryTarget.APP_PROD,"1".repeat(40),"PREPARING",mapper.createObjectNode());
        mvc.perform(post("/api/mobile/delivery/{id}/authorize",op.id()).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"totp\":\"123456\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/mobile/delivery/{id}/confirm",op.id()).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"authorization\":\""+UUID.randomUUID()+"\",\"command\":\"foreign\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(factors,grants);
    }
    @Test void oldUfdClaimIsFoundBeyondUiWindowAndOnlyForExactOwnedHead() {
        String head="1".repeat(40),branch="atenea/change-59315b6e-59bc-4884-9def-356e1ca86ef4";
        var evidence=mapper.createObjectNode();
        evidence.putObject("ufdDispatch").put("headSha",head).put("headBranch",branch)
                .put("requestId",GitHubClient.ufdRequestId(head,branch).toString()).put("authoritySha","2".repeat(40))
                .put("status","UNCONFIRMED").put("claimedAt",1);
        var old=store.create(sessionId,admin.getId(),"PUBLISH_PR",DeliveryTarget.APP_PROD,null,"BLOCKED",evidence);
        jdbc.update("UPDATE mobile_delivery_operation SET created_at=now()-interval '1 hour' WHERE id=?",old.id());
        for (int i=0;i<31;i++) store.create(sessionId,admin.getId(),"PUBLISH_PR",DeliveryTarget.APP_PROD,null,"BLOCKED",mapper.createObjectNode());
        assertFalse(store.list(sessionId).stream().anyMatch(op->op.id().equals(old.id())));
        assertEquals(old.id(),store.ownedHeadUfdRequest(sessionId,head,branch).orElseThrow().id());
        assertTrue(store.ownedHeadUfdRequest(sessionId,"3".repeat(40),branch).isEmpty());
        assertTrue(store.ownedHeadUfdRequest(sessionId,head,branch+"-foreign").isEmpty());
    }
}
