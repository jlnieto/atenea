package com.atenea.attachments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.DeliveryRejectedException;
import com.atenea.persistence.auth.*;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.*;
import com.atenea.persistence.worksession.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"atenea.auth.bootstrap.enabled=false","atenea.attachments.enabled=true","atenea.attachments.real-project-allowlist[0]=atenea"})
@Transactional
class ChangeAttachmentEnablementIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectRepository projects;
    @Autowired DevelopmentChangeRepository changes;
    @Autowired WorkSessionRepository sessions;
    @Autowired OperatorRepository operators;
    @Autowired ChangeAttachmentEnablementService service;
    @MockBean AttachmentWorkerClient worker;
    private WorkSessionEntity session;
    private AuthenticatedOperator actor;

    @BeforeEach void fixture() {
        jdbc.update("INSERT INTO worker_node(id,protocol_version,endpoint,enabled,healthy,normal_capacity,heavy_capacity,capabilities) "
                + "VALUES('ax42-01','agent-run-worker/v1','http://test.invalid',true,true,1,1,'project-codex-v4') ON CONFLICT(id) DO NOTHING");
        jdbc.update("INSERT INTO project(id,name,repo_path,default_base_branch) VALUES(1,'Atenea','/workspace/repos/internal/atenea','main') "
                + "ON CONFLICT(id) DO UPDATE SET name='Atenea',repo_path='/workspace/repos/internal/atenea',default_base_branch='main'");
        var project=projects.findById(1L).orElseThrow(); Instant now=Instant.now(); UUID key=UUID.randomUUID();
        var change=new DevelopmentChangeEntity();change.setProject(project);change.setChangeKey(key);change.setTitle("Ephemeral attachment recovery");
        change.setBaseRef("refs/heads/main");change.setBaseCommit("1".repeat(40));change.setSelectedWorkerId("ax42-01");
        change.setWorkspaceIdentity("remote:ax42-01:change:"+key);change.setWorkspaceBranch("atenea/change-"+key);
        change.setProjectPolicyRevision(1);change.setSourceFingerprintSha256("2".repeat(64));
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        change.setWorkspaceOperationRevision(1);change.setWorkspaceObservationSha256("a".repeat(64));change.setWorkspaceUpdatedAt(now);
        change.setCreatedAt(now);change.setUpdatedAt(now);changes.saveAndFlush(change);
        session=new WorkSessionEntity();session.setProject(project);session.setDevelopmentChange(change);session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle(change.getTitle());session.setBaseBranch("main");session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setWorkspaceIdentity(change.getWorkspaceIdentity());session.setSelectedWorkerId("ax42-01");session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setRemoteSessionId(UUID.randomUUID());session.setRemoteWorkloadKind("project-codex-v1");session.setRemoteCloseState(RemoteCloseState.NOT_STARTED);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);session.setOpenedAt(now);session.setLastActivityAt(now);
        session.setCreatedAt(now);session.setUpdatedAt(now);sessions.saveAndFlush(session);
        var operator=new OperatorEntity();operator.setEmail(UUID.randomUUID()+"@test.invalid");operator.setDisplayName("Test");operator.setPasswordHash("synthetic");
        operator.setActive(true);operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);operator.setCreatedAt(now);operator.setUpdatedAt(now);
        operators.saveAndFlush(operator);actor=new AuthenticatedOperator(operator.getId(),operator.getEmail(),"Test");
        when(worker.health()).thenReturn(new AttachmentWorkerClient.Health(AttachmentProperties.PROTOCOL,"ax42-01",true,
                AttachmentProperties.DEFAULT_MAX_FILE_BYTES,AttachmentProperties.DEFAULT_MAX_SESSION_BYTES,AttachmentProperties.TURN_IMAGE_CONTENT_TYPES,now));
        when(worker.changeProjectCapability()).thenReturn(new AttachmentWorkerClient.RealProjectCapability(AttachmentWorkerClient.CHANGE_PROJECT_PROTOCOL,
                "ax42-01",true,List.of("atenea"),List.of(AttachmentStorageScope.REAL_SESSION),now));
    }

    @Test void sameConversationEnablesIdempotentlyWithOneDurableAuditAndNoRuns() {
        long before=sessions.count();var remote=session.getRemoteSessionId();
        assertEquals(AttachmentCapability.State.READY,service.enable(session.getId(),actor).state());
        UUID operation=jdbc.queryForObject("SELECT operation_id FROM work_session_attachment_enablement WHERE session_id=?",UUID.class,session.getId());
        assertEquals(AttachmentCapability.State.READY,service.enable(session.getId(),actor).state());
        assertEquals(operation,jdbc.queryForObject("SELECT operation_id FROM work_session_attachment_enablement WHERE session_id=?",UUID.class,session.getId()));
        assertEquals(before,sessions.count());assertEquals(remote,session.getRemoteSessionId());
        assertEquals(AttachmentOwnership.CHANGE_POLICY,session.getAttachmentPolicyRevision());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE session_id=?",Integer.class,session.getId()));
    }

    @Test void pausedChangeCannotBeReopenedByEnablingImages() {
        session.getDevelopmentChange().setStatus(DevelopmentChangeStatus.PAUSED);changes.flush();
        assertEquals("OPEN_CHANGE_WORKSESSION_REQUIRED",assertThrows(DeliveryRejectedException.class,() -> service.enable(session.getId(),actor)).code());
        assertNull(session.getAttachmentPolicyRevision());
    }

    @Test void unavailableWorkerRollsBackPolicyAndDoesNotCreateAudit() {
        when(worker.changeProjectCapability()).thenReturn(null);
        assertEquals("ATTACHMENT_WORKER_NOT_READY",assertThrows(DeliveryRejectedException.class,() -> service.enable(session.getId(),actor)).code());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM work_session_attachment_enablement WHERE session_id=?",Integer.class,session.getId()));
    }
}
