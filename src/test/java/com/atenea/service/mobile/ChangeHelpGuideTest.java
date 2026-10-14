package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ChangeHelpGuideTest {
    private String stage(ChangeHelpFixture f) { return ChangeHelpGuide.explain(f.build(),"¿Qué hago?",false).stage(); }

    @Test void realGitInstructionBlockerIsPendingValidationNotFailedTests() {
        var result=ChangeHelpGuide.explain(new ChangeHelpFixture().build(),"Me he quedado bloqueado",false);
        assertEquals("VALIDATION_REQUIRED",result.stage());
        assertEquals("Ir a Validar cambio",result.nextAction().label());
        assertEquals("CHANGE",result.nextAction().destination());
        assertTrue(result.answer().contains("no es un resultado de pruebas fallidas"));
        assertTrue(result.facts().contains("Pruebas de esta revisión: 0/4 correctas."));
    }
    @Test void easierExplanationKeepsSameScopeRevisionAndDestination() {
        var f=new ChangeHelpFixture();
        var detailed=ChangeHelpGuide.explain(f.build(),"Por qué",false);
        var easy=ChangeHelpGuide.explain(f.build(),"No entiendo",true);
        assertTrue(easy.answer().length()<detailed.answer().length());
        assertEquals(detailed.nextAction(),easy.nextAction());
        assertEquals(detailed.sourceRevision(),easy.sourceRevision());
        assertEquals(detailed.changeKey(),easy.changeKey());
    }
    @Test void activeRunOnlyOffersProgress() {
        var f=new ChangeHelpFixture(); f.activeRun=true;
        var r=ChangeHelpGuide.explain(f.build(),"Publica ahora",false);
        assertEquals("RUNNING",r.stage()); assertEquals("ACTIVITY",r.nextAction().destination());
    }
    @Test void runningValidationDoesNotOfferAnotherValidation() {
        var f=new ChangeHelpFixture(); f.activeValidation=true;
        assertEquals("VALIDATING",stage(f));
    }
    @Test void sourceRecoveryComesBeforeValidation() {
        var f=new ChangeHelpFixture(); f.sourceUpdate=true;
        assertEquals("SOURCE_UPDATE",stage(f));
    }
    @Test void ownershipConflictNeverSuggestsIdentityChangesOrNewSession() {
        var f=new ChangeHelpFixture(); f.workspace="BLOCKED";
        assertEquals("WORKSPACE_ATTENTION",stage(f));
    }
    @Test void failedRunIsNotPresentedAsFailedValidation() {
        var f=new ChangeHelpFixture(); f.runStatus="FAILED"; f.failure="CODEX_EXECUTION_FAILED";
        var r=ChangeHelpGuide.explain(f.build(),"Reintenta",false);
        assertEquals("TASK_ATTENTION",r.stage()); assertEquals("ACTIVITY",r.nextAction().destination());
        assertTrue(r.answer().contains("No dupliques"));
    }
    @Test void actualFailedValidationIsDistinguishedAndPreserved() {
        var f=new ChangeHelpFixture(); f.check="BACKEND_TEST"; f.checkFailure="[TESTS_FAILED] Closed validation failed";
        var r=ChangeHelpGuide.explain(f.build(),"Qué ocurre",false);
        assertEquals("VALIDATION_ATTENTION",r.stage());
        assertTrue(r.facts().stream().anyMatch(s -> s.contains("TESTS_FAILED")));
    }
    @Test void projectionWithoutFourCurrentResultsDoesNotAssertPass() {
        var f=new ChangeHelpFixture(); f.validation="CURRENT";
        assertEquals("VALIDATION_EVIDENCE_MISSING",stage(f));
    }
    @Test void currentValidationOffersPrNotDeployment() {
        var f=new ChangeHelpFixture(); f.validation="CURRENT"; f.passed=4;
        var r=ChangeHelpGuide.explain(f.build(),"¿Qué falta para publicar?",false);
        assertEquals("READY_FOR_PR",r.stage()); assertTrue(r.answer().contains("no despliega"));
    }
    @Test void recordedPrDoesNotClaimCiOrMergeabilityPass() {
        var f=new ChangeHelpFixture(); f.validation="CURRENT"; f.passed=4; f.pr="OPEN";
        assertEquals("PULL_REQUEST",stage(f));
    }
    @Test void mergeAndPublicationAreSeparate() {
        var f=new ChangeHelpFixture(); f.validation="CURRENT"; f.passed=4; f.pr="MERGED";
        var r=ChangeHelpGuide.explain(f.build(),"Diferencia entre integrar y publicar",false);
        assertEquals("PUBLICATION",r.stage()); assertTrue(r.answer().contains("pasos distintos"));
    }
    @Test void durableDeliveryPendingAndFailedDoNotResubmit() {
        var f=new ChangeHelpFixture(); f.validation="CURRENT"; f.passed=4;
        f.deliveryKind="PUBLISH_PR"; f.deliveryState="WAITING_CI";
        assertEquals("DELIVERY_PENDING",stage(f));
        f.deliveryState="BLOCKED"; f.deliveryError="CI_FAILED";
        assertEquals("DELIVERY_ATTENTION",stage(f));
    }
    @Test void completedChangeOnlyOffersHistory() {
        var f=new ChangeHelpFixture(); f.change="COMPLETED"; f.session="CLOSED";
        var r=ChangeHelpGuide.explain(f.build(),"Reabre",false);
        assertEquals("COMPLETED",r.stage()); assertEquals("CHAT",r.nextAction().destination());
    }
    @Test void healthyUnchangedWorkspaceOffersExistingConversation() {
        var f=new ChangeHelpFixture(); f.source="CLEAN";
        assertEquals("IMPLEMENTATION",stage(f));
    }
    @Test void blockedProjectionWithNoFailureDoesNotInventTestsFailureOrRetry() {
        var f=new ChangeHelpFixture(); f.validation="BLOCKED";
        assertEquals("VALIDATION_UNKNOWN_BLOCKER",stage(f));
    }
    @Test void closedSessionDoesNotInventCompletedChange() {
        var f=new ChangeHelpFixture(); f.session="CLOSED";
        assertEquals("HISTORY",stage(f));
    }
    @Test void routineRoleOrDisabledDeliveryCannotBePresentedAsPublicationAvailable() {
        var f=new ChangeHelpFixture(); f.validation="CURRENT"; f.passed=4;
        f.operatorRole="ROUTINE_OPERATOR"; assertEquals("DELIVERY_NOT_AVAILABLE",stage(f));
        f.operatorRole="PLATFORM_ADMINISTRATOR"; f.deliveryEnabled=false;
        assertEquals("DELIVERY_NOT_AVAILABLE",stage(f));
    }
    @Test void unknownProjectCatalogDoesNotInventAteneaChecksAndPublication() {
        var f=new ChangeHelpFixture(); f.required=0;
        var r=ChangeHelpGuide.explain(f.build(),"Valida",false);
        assertEquals("WORKFLOW_NOT_CONFIRMED",r.stage());
        assertEquals("CHAT",r.nextAction().destination());
    }
}
