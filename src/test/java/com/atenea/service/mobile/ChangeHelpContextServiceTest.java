package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.DeliveryStore;
import com.atenea.delivery.ReleaseControlClient;
import com.atenea.delivery.MobileDeliveryService;
import com.atenea.delivery.DeliveryOperation;
import com.atenea.delivery.DeliveryTarget;
import com.atenea.delivery.ReleaseRecoverySource;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.*;
import com.atenea.service.worksession.WorkSessionOperationBlockedException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class ChangeHelpContextServiceTest {
    private final WorkSessionRepository sessions=mock(WorkSessionRepository.class);
    private final AgentRunRepository runs=mock(AgentRunRepository.class);
    private final ValidationOperationRepository validations=mock(ValidationOperationRepository.class);
    private final OperatorRepository operators=mock(OperatorRepository.class);
    private final DeliveryStore deliveries=mock(DeliveryStore.class);
    private final ReleaseControlClient releaseControl=mock(ReleaseControlClient.class);
    private final MobileDeliveryService observations=mock(MobileDeliveryService.class);
    private final ChangeHelpContextService service=new ChangeHelpContextService(sessions,runs,validations,operators,deliveries,releaseControl,observations);
    private final AuthenticatedOperator actor=new AuthenticatedOperator(1L,"operator@example.test","Operator");
    private WorkSessionEntity session;
    private DevelopmentChangeEntity change;
    @BeforeEach void setup() {
        var operator=new OperatorEntity(); operator.setId(1L); operator.setActive(true);
        when(operators.findById(1L)).thenReturn(Optional.of(operator));
        var project=new ProjectEntity(); project.setId(1L);
        change=new DevelopmentChangeEntity(); change.setProject(project); change.setChangeKey(UUID.randomUUID());
        change.setWorkspaceIdentity("remote:ax42-01:change:"+change.getChangeKey());
        change.setWorkspaceBranch("atenea/change-"+change.getChangeKey()); change.setSelectedWorkerId("ax42-01");
        change.setSourceFingerprintSha256("4".repeat(64)); change.setSourceRevision(1);
        change.setSourceState(DevelopmentChangeSourceState.DIRTY); change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        session=new WorkSessionEntity(); session.setId(22L); session.setProject(project); session.setDevelopmentChange(change);
        session.setWorkspaceIdentity(change.getWorkspaceIdentity()); session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setSelectedWorkerId(change.getSelectedWorkerId());
        when(sessions.findWithProjectAndDevelopmentChangeById(22L)).thenReturn(Optional.of(session));
    }
    @Test void readsRecordedStateWithoutLocksWritesWorkerOrValidationExecution() throws Exception {
        var first=service.read(22L,actor); var second=service.read(22L,actor);
        assertEquals(0,first.passedChecks()); assertEquals(first.changeKey(),second.changeKey());
        verify(sessions,never()).save(any()); verify(runs,never()).save(any());
        verify(validations,never()).save(any()); verify(deliveries,never()).create(any(),any(),any(),any(),any(),any(),any());
        verify(sessions,never()).findLockedWithProjectAndDevelopmentChangeById(any());
        verify(releaseControl,times(2)).enabled(); verifyNoMoreInteractions(releaseControl);
        verifyNoInteractions(observations);
        assertTrue(ChangeHelpContextService.class.getMethod("read",Long.class,AuthenticatedOperator.class)
                .getAnnotation(Transactional.class).readOnly());
    }
    @Test void rejectsUnboundAndForeignWorkspaceBeforeReadingOtherRecords() {
        session.setWorkspaceIdentity("foreign");
        assertThrows(WorkSessionOperationBlockedException.class,()->service.read(22L,actor));
        verifyNoInteractions(runs,validations,deliveries);
        session.setDevelopmentChange(null);
        assertThrows(WorkSessionOperationBlockedException.class,()->service.read(22L,actor));
    }
    @Test void rejectsOtherProjectAndEmptyOwnership() {
        var other=new ProjectEntity(); other.setId(2L); session.setProject(other);
        assertThrows(WorkSessionOperationBlockedException.class,()->service.read(22L,actor));
        session.setProject(change.getProject()); change.setSelectedWorkerId(null); session.setSelectedWorkerId(null);
        assertThrows(WorkSessionOperationBlockedException.class,()->service.read(22L,actor));
    }
    @Test void inactiveOperatorCannotReadState() {
        when(operators.findById(1L)).thenReturn(Optional.empty());
        assertThrows(WorkSessionOperationBlockedException.class,()->service.read(22L,actor));
        verifyNoInteractions(runs,validations,deliveries);
    }
    @Test void oldDefinitionAndOtherFingerprintDoNotCount() {
        var old=new ValidationOperationEntity(); old.setOperation(ValidationOperationKind.BACKEND_TEST);
        old.setStatus(ValidationOperationStatus.SUCCEEDED); old.setDefinitionRevision("old");
        old.setSourceTreeFingerprintSha256("4".repeat(64));
        var other=new ValidationOperationEntity(); other.setOperation(ValidationOperationKind.WEB_BUILD);
        other.setStatus(ValidationOperationStatus.SUCCEEDED); other.setDefinitionRevision(ValidationOperationKind.WEB_BUILD.definitionRevision());
        other.setSourceTreeFingerprintSha256("5".repeat(64));
        when(validations.findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(22L,"4".repeat(64)))
                .thenReturn(List.of(old,other));
        assertEquals(0,service.read(22L,actor).passedChecks());
    }
    @Test void currentFailureIsReturnedWithoutSecretsPathsOrUrls() {
        var failed=new ValidationOperationEntity(); failed.setOperation(ValidationOperationKind.BACKEND_TEST);
        failed.setStatus(ValidationOperationStatus.FAILED); failed.setDefinitionRevision(ValidationOperationKind.BACKEND_TEST.definitionRevision());
        failed.setSourceTreeFingerprintSha256("4".repeat(64));
        failed.setSummary("[TESTS_FAILED] Bearer SECRET /srv/secret/token https://example.test/private");
        when(validations.findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(22L,"4".repeat(64)))
                .thenReturn(List.of(failed));
        var result=service.read(22L,actor); assertEquals("BACKEND_TEST",result.failedCheck());
        assertTrue(result.validationFailure().contains("TESTS_FAILED"));
        assertFalse(result.validationFailure().contains("SECRET"));
        assertFalse(result.validationFailure().contains("/srv"));
        assertFalse(result.validationFailure().contains("https:"));
    }
    @Test void assignmentSecretsAndJwtDoNotReachExplanations() {
        String redacted=ChangeHelpContextService.safeText("PASSWORD=synthetic-secret token:another-secret API_KEY=key-secret");
        assertFalse(redacted.contains("synthetic-secret")); assertFalse(redacted.contains("another-secret"));
        assertFalse(redacted.contains("key-secret"));
    }
    @Test void recoveryPublicationUsesExactIntegrationProofRatherThanMatchingPrHeadToMain() {
        session.setFinalCommitSha("1".repeat(40));
        var integrated=integrated();
        var release=recovery(integrated);
        when(deliveries.list(22L)).thenReturn(List.of(release,integrated));
        var result=service.read(22L,actor);
        assertEquals("RELEASE",result.deliveryKind()); assertEquals("READY",result.deliveryState());
        assertEquals("APP_PROD",result.deliveryTarget());
        assertEquals(Instant.parse("2026-10-14T08:10:00Z").getEpochSecond(),result.deliveryExpiresAt());
        assertEquals("1".repeat(40),session.getFinalCommitSha());
        verifyNoInteractions(observations); verify(deliveries,never()).update(any(),any(),any(),any(),any());
    }
    @Test void wrongIntegrationProofOtherRevisionAndForeignOwnerCannotBecomeTheNextPublication() {
        session.setFinalCommitSha("1".repeat(40)); var integrated=integrated(); var release=recovery(integrated);
        var bad=release.evidence().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("releaseRecovery")).put("integratedMergeCommit","9".repeat(40));
        var candidates=List.of(
                new DeliveryOperation(release.id(),22L,1L,"RELEASE",DeliveryTarget.APP_PROD,release.sourceCommit(),
                    release.executionId(),"READY",release.planSha256(),bad,null,release.createdAt(),release.updatedAt()),
                new DeliveryOperation(release.id(),22L,2L,"RELEASE",DeliveryTarget.APP_PROD,release.sourceCommit(),
                    release.executionId(),"READY",release.planSha256(),release.evidence(),null,release.createdAt(),release.updatedAt()),
                new DeliveryOperation(release.id(),23L,1L,"RELEASE",DeliveryTarget.APP_PROD,release.sourceCommit(),
                    release.executionId(),"READY",release.planSha256(),release.evidence(),null,release.createdAt(),release.updatedAt()));
        for (var candidate:candidates) {
            when(deliveries.list(22L)).thenReturn(List.of(candidate,integrated));
            assertEquals("INTEGRATE",service.read(22L,actor).deliveryKind());
        }
        session.setFinalCommitSha("8".repeat(40));
        when(deliveries.list(22L)).thenReturn(List.of(release,integrated));
        assertNull(service.read(22L,actor).deliveryKind());
    }
    @Test void normalAndroidReleaseAndPrReceiptAreBoundToTheirActualHeadAndMerge() {
        session.setFinalCommitSha("1".repeat(40)); var integrated=integrated();
        var evidence=new ObjectMapper().createObjectNode();
        var android=new DeliveryOperation(UUID.randomUUID(),22L,1L,"RELEASE",DeliveryTarget.ANDROID_STABLE,"2".repeat(40),
                UUID.randomUUID(),"PREPARING",null,evidence,null,Instant.now(),Instant.now());
        when(deliveries.list(22L)).thenReturn(List.of(android,integrated));
        assertEquals("ANDROID_STABLE",service.read(22L,actor).deliveryTarget());
        evidence.put("headCommit","1".repeat(40));
        var pr=new DeliveryOperation(UUID.randomUUID(),22L,1L,"PUBLISH_PR",DeliveryTarget.APP_PROD,null,
                UUID.randomUUID(),"SUCCEEDED",null,evidence,null,Instant.now(),Instant.now());
        when(deliveries.list(22L)).thenReturn(List.of(pr));
        assertEquals("PUBLISH_PR",service.read(22L,actor).deliveryKind());
    }
    @Test void mergedChangeReadsTheSameReadOnlyDeploymentObservationAsCambioWithoutPlanningOrReconciling() {
        session.setPullRequestStatus(WorkSessionPullRequestStatus.MERGED);
        var operator=new OperatorEntity(); operator.setId(1L); operator.setActive(true);
        operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        when(operators.findById(1L)).thenReturn(Optional.of(operator));
        when(releaseControl.enabled()).thenReturn(true);
        when(observations.observeDeployment(22L,actor)).thenReturn(new MobileDeliveryService.DeploymentObservation(
                22L,"DEPLOYED","OPERATOR","3".repeat(40),"2".repeat(40),true,Instant.now().getEpochSecond(),
                UUID.randomUUID(),UUID.randomUUID(),"4".repeat(64),null));
        assertEquals("DEPLOYED",service.read(22L,actor).deploymentStatus());
        verify(observations).observeDeployment(22L,actor); verifyNoMoreInteractions(observations);
        verify(deliveries,never()).create(any(),any(),any(),any(),any(),any(),any());
        when(observations.observeDeployment(22L,actor)).thenThrow(new RuntimeException("transport"));
        assertEquals("UNAVAILABLE",service.read(22L,actor).deploymentStatus());
    }
    private DeliveryOperation integrated() {
        return new DeliveryOperation(UUID.randomUUID(),22L,1L,"INTEGRATE",DeliveryTarget.APP_PROD,"1".repeat(40),
                UUID.randomUUID(),"SUCCEEDED",null,new ObjectMapper().createObjectNode().put("mergeCommit","2".repeat(40)),
                null,Instant.now(),Instant.now());
    }
    private DeliveryOperation recovery(DeliveryOperation integrated) {
        var mapper=new ObjectMapper();
        var evidence=mapper.createObjectNode().put("expiresAt",Instant.parse("2026-10-14T08:10:00Z").getEpochSecond());
        evidence.set("releaseRecovery",ReleaseRecoverySource.create(integrated,"3".repeat(40)).json(mapper));
        return new DeliveryOperation(UUID.randomUUID(),22L,1L,"RELEASE",DeliveryTarget.APP_PROD,"3".repeat(40),
                UUID.randomUUID(),"READY","4".repeat(64),evidence,null,Instant.now(),Instant.now());
    }
}
