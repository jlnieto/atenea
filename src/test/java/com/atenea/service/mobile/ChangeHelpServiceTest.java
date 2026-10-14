package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.atenea.auth.AuthenticatedOperator;
import org.junit.jupiter.api.Test;

class ChangeHelpServiceTest {
    private final ChangeHelpContextService contexts=mock(ChangeHelpContextService.class);
    private final ChangeHelpExplainer explainer=mock(ChangeHelpExplainer.class);
    private final ChangeHelpService service=new ChangeHelpService(contexts,explainer);
    private final AuthenticatedOperator actor=new AuthenticatedOperator(1L,"operator@example.test","Operator");
    private void context() { when(contexts.read(22L,actor)).thenReturn(new ChangeHelpFixture().build()); }

    @Test void overviewReadsWithoutCallingModel() {
        context(); assertEquals("VALIDATION_REQUIRED",service.overview(22L,actor).stage());
        verifyNoInteractions(explainer);
    }
    @Test void missingModelStillProvidesFunctionalHelp() {
        context(); var result=service.ask(22L,actor,"Qué hago",false);
        assertEquals("RECORDED_STATE",result.explanationSource());
        assertEquals("CHANGE",result.nextAction().destination());
    }
    @Test void explicitSimplificationWorksWithoutModel() {
        context(); var result=service.ask(22L,actor,"No lo entiendo. Explícamelo más fácil",false);
        assertEquals(result.simpleAnswer(),result.answer());
    }
    @Test void providerTimeoutDoesNotHideRecordedGuidance() throws Exception {
        context(); when(explainer.available()).thenReturn(true);
        when(explainer.explain(any(),anyString(),any())).thenThrow(new java.net.http.HttpTimeoutException("timeout"));
        assertEquals("RECORDED_STATE",service.ask(22L,actor,"Qué hago",false).explanationSource());
    }
    @Test void explanationCannotSupplyCommandsOrChangeNextAction() throws Exception {
        context(); when(explainer.available()).thenReturn(true);
        when(explainer.explain(any(),anyString(),any())).thenReturn("Ejecuta sudo y ssh para forzar el bloqueo");
        var result=service.ask(22L,actor,"Hazlo",false);
        assertEquals("RECORDED_STATE",result.explanationSource());
        assertEquals("CHANGE",result.nextAction().destination());
    }
    @Test void safeNaturalExplanationPreservesAllAuthoritativeFields() throws Exception {
        context(); when(explainer.available()).thenReturn(true);
        when(explainer.explain(any(),anyString(),any())).thenReturn("El trabajo está guardado. Abre Cambio para comprobarlo.");
        var original=service.overview(22L,actor);
        var result=service.ask(22L,actor,"Qué hago",false);
        assertEquals("RECORDED_STATE_WITH_AI_EXPLANATION",result.explanationSource());
        assertEquals(original.facts(),result.facts()); assertEquals(original.nextAction(),result.nextAction());
        assertEquals(original.sourceRevision(),result.sourceRevision());
    }
    @Test void eachFollowupReadsFreshStateRatherThanOldConversation() {
        var first=new ChangeHelpFixture(); var second=new ChangeHelpFixture(); second.validation="CURRENT"; second.passed=4; second.revision=2;
        when(contexts.read(22L,actor)).thenReturn(first.build(),second.build());
        assertEquals("VALIDATION_REQUIRED",service.ask(22L,actor,"Qué hago",false).stage());
        var next=service.ask(22L,actor,"Y ahora",false);
        assertEquals(2,next.sourceRevision()); assertEquals("READY_FOR_PR",next.stage());
        verify(contexts,times(2)).read(22L,actor);
    }
    @Test void modelCannotClaimPassOrPublicationWhenTestsArePending() throws Exception {
        context(); when(explainer.available()).thenReturn(true);
        when(explainer.explain(any(),anyString(),any())).thenReturn("Las pruebas han pasado. Puedes publicar ahora.");
        assertEquals("RECORDED_STATE",service.ask(22L,actor,"Quiero publicar",false).explanationSource());
    }
}
