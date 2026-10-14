package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.DeliveryStore;
import com.atenea.delivery.ReleaseControlClient;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.*;
import com.atenea.service.worksession.WorkSessionOperationBlockedException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    private final ChangeHelpContextService service=new ChangeHelpContextService(sessions,runs,validations,operators,deliveries,releaseControl);
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
}
