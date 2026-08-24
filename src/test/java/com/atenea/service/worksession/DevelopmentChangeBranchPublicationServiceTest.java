package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.DevelopmentChangeBranchPublication;
import com.atenea.remoteworker.DevelopmentChangeBranchPublicationCommand;
import com.atenea.remoteworker.DevelopmentChangeBranchPublicationGateway;
import com.atenea.remoteworker.ProjectCodexIdentity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class DevelopmentChangeBranchPublicationServiceTest {

    @Mock private WorkSessionRepository sessionRepository;
    @Mock private AgentRunRepository agentRunRepository;
    @Mock private DevelopmentChangeBranchPublicationGateway gateway;
    @Mock private PlatformTransactionManager transactionManager;

    private DevelopmentChangeBranchPublicationService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new DevelopmentChangeBranchPublicationService(
                sessionRepository, agentRunRepository, gateway, transactionManager);
    }

    @Test
    void publishesAndPersistsExactDevelopmentChangeIdentityBeforePrCreation() {
        WorkSessionEntity session = session();
        when(sessionRepository.findLockedWithProjectAndDevelopmentChangeById(12L))
                .thenReturn(Optional.of(session));
        when(gateway.publish(any())).thenReturn(publication());
        when(sessionRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        ArgumentCaptor<DevelopmentChangeBranchPublicationCommand> command =
                ArgumentCaptor.forClass(DevelopmentChangeBranchPublicationCommand.class);

        var identity = service.publish(12L);

        verify(gateway).publish(command.capture());
        assertEquals(session.getDevelopmentChange().getChangeKey(), command.getValue().changeKey());
        assertEquals(session.getDevelopmentChange().getSourceFingerprintSha256(),
                command.getValue().sourceFingerprintSha256());
        assertEquals(publication().publishedHeadSha(), session.getFinalCommitSha());
        assertEquals(publication().publicationReceiptSha256(),
                session.getPublicationReceiptSha256());
        assertEquals(identity.changeKey(), session.getPublishedChangeKey());
        assertEquals("jlnieto/atenea", session.getPublishedRepository());
    }

    @Test
    void exactReplayIsIdempotentAndDoesNotRewritePublishedIdentity() {
        WorkSessionEntity session = session();
        when(sessionRepository.findLockedWithProjectAndDevelopmentChangeById(12L))
                .thenReturn(Optional.of(session));
        when(gateway.publish(any())).thenReturn(publication());
        when(sessionRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        service.publish(12L);
        service.publish(12L);

        verify(sessionRepository).saveAndFlush(session);
        verify(gateway, org.mockito.Mockito.times(2)).publish(any());
    }

    @Test
    void staleOrCrossOwnedChangeFailsBeforePlatformPublication() {
        WorkSessionEntity session = session();
        session.getDevelopmentChange().setSourceState(DevelopmentChangeSourceState.STALE);
        when(sessionRepository.findLockedWithProjectAndDevelopmentChangeById(12L))
                .thenReturn(Optional.of(session));

        assertThrows(WorkSessionPublishConflictException.class, () -> service.publish(12L));

        verify(gateway, never()).publish(any());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    private static WorkSessionEntity session() {
        UUID changeKey = UUID.fromString("8bf60472-3c0e-49aa-99bf-6dc3c7e60eaf");
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        project.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());

        DevelopmentChangeEntity change = new DevelopmentChangeEntity();
        change.setId(81L);
        change.setChangeKey(changeKey);
        change.setProject(project);
        change.setBaseRef("refs/heads/main");
        change.setBaseCommit("1".repeat(40));
        change.setObservedCanonicalCommit("2".repeat(40));
        change.setWorkspaceBranch("atenea/change-" + changeKey);
        change.setWorkspaceIdentity("remote:ax42-01:change:" + changeKey);
        change.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        change.setSourceRevision(3L);
        change.setSourceFingerprintSha256("a".repeat(64));
        change.setWorkspaceOwnershipFingerprintSha256("b".repeat(64));
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        change.setSourceState(DevelopmentChangeSourceState.DIRTY);

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setProject(project);
        session.setDevelopmentChange(change);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceIdentity(change.getWorkspaceIdentity());
        session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceRef(change.getBaseRef());
        session.setCanonicalSourceCommit(change.getBaseCommit());
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setUpdatedAt(Instant.now());
        return session;
    }

    private static DevelopmentChangeBranchPublication publication() {
        return new DevelopmentChangeBranchPublication(
                "3".repeat(40),
                DevelopmentChangeBranchPublication.RemoteDisposition.CREATED,
                "c".repeat(64),
                "d".repeat(64));
    }
}
