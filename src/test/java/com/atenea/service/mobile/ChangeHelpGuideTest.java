package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ChangeHelpGuideTest {
    @Test void unsupportedManifestExplainsNoExecutionAndOffersRecoveryNotBlindRetry() {
        var f=new ChangeHelpFixture();f.check="ANDROID_BUILD";f.checkFailure="[UNSUPPORTED_DEPENDENCY_MANIFEST] mismatch";
        var result=ChangeHelpGuide.explain(f.build(),"¿Qué hago?",false);
        assertEquals("VALIDATION_ENVIRONMENT_ATTENTION",result.stage());
        assertEquals("VALIDATION_RECOVERY",result.nextAction().destination());
        assertTrue(result.answer().contains("Android no pudo empezar"));
        assertTrue(result.answer().contains("no lo resolverá"));
        assertFalse(result.answer().contains("SSH"));
        assertTrue(ChangeHelpGuide.explain(f.build(),"Más fácil",true).answer().contains("no repitas"));
    }
    @Test void apkQuestionUsesRealChannelWithoutRequiringValidationAndOffersRealUpdateDestination() {
        var f=new ChangeHelpFixture();f.source="CLEAN";f.validation="NOT_STARTED";f.installedVersion=155;
        var apk=new com.atenea.delivery.ReleaseControlClient.AndroidObservation("8".repeat(40),"0.6.12",1000012,"a".repeat(64),"b".repeat(64),
                java.time.Instant.now().getEpochSecond(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),"c".repeat(64));
        f.readiness=new com.atenea.delivery.ChangeDeliveryReadiness(22L,"NOT_INTEGRATED",null,null,java.util.List.of(),false,null,apk,"NOT_INTEGRATED",null,java.time.Instant.now().getEpochSecond());
        var answer=ChangeHelpGuide.explain(f.build(),"Instalé una versión pero el menú sigue igual",false);
        assertEquals("ANDROID_CHANNEL",answer.stage());assertEquals("APP_UPDATES",answer.nextAction().destination());
        assertTrue(answer.answer().contains("1000012"));assertTrue(answer.answer().contains("sin validar"));
        f.session="CLOSED";f.change="COMPLETED";
        assertEquals("APP_UPDATES",ChangeHelpGuide.explain(f.build(),"¿Qué APK hay?",false).nextAction().destination());
    }

    @Test void healthyBackendCannotRecommendFinalizationWhenAndroidIsRequiredButMissing() {
        var f=new ChangeHelpFixture();f.validation="CURRENT";f.passed=4;f.pr="MERGED";f.deploymentStatus="DEPLOYED";
        f.readiness=new com.atenea.delivery.ChangeDeliveryReadiness(22L,"CONFIRMED","1".repeat(40),"2".repeat(40),
                java.util.List.of(com.atenea.delivery.DeliveryTarget.ANDROID_STABLE),false,null,null,"NOT_INCLUDED",null,java.time.Instant.now().getEpochSecond());
        var answer=ChangeHelpGuide.explain(f.build(),"¿Qué hago ahora?",false);
        assertEquals("ANDROID_PUBLICATION_REQUIRED",answer.stage());assertEquals("DELIVERY",answer.nextAction().destination());
        assertTrue(answer.answer().contains("Preparar Android estable"));
    }
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
    @Test void preparedPublicationRequiresUserConfirmationNotWaitingOrPreparingAgain() {
        var f=publication("READY"); f.deliveryExpiresAt=f.build().observedAt().getEpochSecond()+600;
        var r=ChangeHelpGuide.explain(f.build(),"¿Tengo que esperar?",false);
        assertEquals("PUBLICATION_CONFIRMATION",r.stage());
        assertEquals("Ir a Confirmar Backend PROD",r.nextAction().label());
        assertTrue(r.answer().contains("no está trabajando")); assertTrue(r.answer().contains("autenticador"));
        f.deliveryTarget="ANDROID_STABLE";
        assertEquals("Ir a Confirmar Android estable",ChangeHelpGuide.explain(f.build(),"Qué hago",false).nextAction().label());
    }
    @Test void preparingOrWaitingCiClearlyContinuesWithoutKeepingThePhoneOpen() {
        var f=publication("PREPARING");
        var r=ChangeHelpGuide.explain(f.build(),"Qué hago",false);
        assertEquals("DELIVERY_PENDING",r.stage()); assertTrue(r.answer().contains("Puedes salir"));
        assertTrue(r.answer().contains("Aún no está publicando"));
        f.deliveryState="WAITING_CI";
        assertTrue(ChangeHelpGuide.explain(f.build(),"Qué hago",false).answer().contains("GitHub"));
    }
    @Test void expiredReadyPlanNeverOffersConfirmationAndClosedExpiredPlanExplainsNewPreparation() {
        var f=publication("READY"); f.deliveryExpiresAt=f.build().observedAt().getEpochSecond();
        assertEquals("DELIVERY_ATTENTION",stage(f));
        assertFalse(ChangeHelpGuide.explain(f.build(),"Qué hago",false).nextAction().label().contains("Confirmar"));
        f.deliveryState="BLOCKED"; f.deliveryError="PLAN_EXPIRED";
        assertEquals("Ir a Preparar Backend PROD",ChangeHelpGuide.explain(f.build(),"Qué hago",false).nextAction().label());
    }
    @Test void verifiedOperatorDeploymentSupersedesExpiredHistoricalPreparationButNotAnActivePublication() {
        var f=publication("BLOCKED"); f.deliveryError="PLAN_EXPIRED"; f.deploymentStatus="DEPLOYED";
        assertEquals("COMPLETION_READY",stage(f));
        assertEquals("Ir a Finalizar cambio",ChangeHelpGuide.explain(f.build(),"Qué hago",false).nextAction().label());
        f.deliveryState="APPLYING"; f.deliveryError=null;
        assertEquals("DELIVERY_PENDING",stage(f));
        f.deliveryState="ROLLBACK_FAILED"; f.deliveryError="FAILED";
        assertEquals("DELIVERY_ATTENTION",stage(f));
    }
    @Test void unavailableOrUnhealthyDeploymentDoesNotSuggestFinishingOrInventPass() {
        var f=publication("SUCCEEDED"); f.deploymentStatus="UNHEALTHY";
        assertEquals("DELIVERY_ATTENTION",stage(f));
        f.deploymentStatus="UNAVAILABLE";
        assertEquals("Ir a Consultar estado",ChangeHelpGuide.explain(f.build(),"Qué hago",false).nextAction().label());
    }
    @Test void missingPlanHashCannotOfferConfirmationOrInventExpiry() {
        var f=publication("READY"); f.deliveryExpiresAt=f.build().observedAt().getEpochSecond()+600;
        f.deliveryPlanVerified=false;
        var r=ChangeHelpGuide.explain(f.build(),"Qué hago",false);
        assertEquals("DELIVERY_ATTENTION",r.stage());
        assertEquals("Ir a Consultar estado",r.nextAction().label());
        assertFalse(r.answer().contains("ha caducado"));
    }
    @Test void healthyBackendCannotHideAnotherTargetsFailedPublicationOrOfferFinalization() {
        for (var target:java.util.List.of("ANDROID_STABLE","AX42_PLATFORM")) {
            var f=publication("BLOCKED"); f.deliveryTarget=target; f.deliveryError="CANONICAL_MAIN_MOVED";
            f.deploymentStatus="DEPLOYED";
            var r=ChangeHelpGuide.explain(f.build(),"Qué hago",false);
            assertEquals("DELIVERY_ATTENTION",r.stage());
            assertTrue(r.nextAction().label().contains("Ver resultado"));
            assertFalse(r.nextAction().label().contains("Finalizar"));
            assertFalse(r.answer().contains("prepara de nuevo"));
        }
    }
    private ChangeHelpFixture publication(String state) {
        var f=new ChangeHelpFixture(); f.validation="CURRENT"; f.passed=4; f.pr="MERGED";
        f.deliveryKind="RELEASE"; f.deliveryState=state; return f;
    }
}
