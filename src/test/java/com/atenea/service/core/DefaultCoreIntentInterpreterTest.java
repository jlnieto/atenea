package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atenea.api.core.CoreRequestContext;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreChannel;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultCoreIntentInterpreterTest {

    @Mock
    private CoreProjectResolver coreProjectResolver;

    @Mock
    private CoreWorkSessionResolver coreWorkSessionResolver;

    private DefaultCoreIntentInterpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new DefaultCoreIntentInterpreter(coreProjectResolver, coreWorkSessionResolver);
    }

    @Test
    void interpretExtractsCleanSessionTitleFromNaturalLanguageRequest() {
        ProjectEntity project = project(7L, "Atenea Core");
        when(coreProjectResolver.requireById(7L)).thenReturn(project);

        CoreInterpretationResult result = interpreter.interpret(new CreateCoreCommandRequest(
                "abre una sesion para Atenea Core: preparar publish final",
                CoreChannel.TEXT,
                new CoreRequestContext(7L, null),
                null));

        assertEquals("create_work_session", result.proposal().capability());
        assertEquals(Map.of("projectId", 7L, "title", "preparar publish final"), result.proposal().parameters());
        verifyNoInteractions(coreWorkSessionResolver);
    }

    @Test
    void interpretFallsBackToProjectBasedTitleWhenRequestDoesNotCarryOne() {
        ProjectEntity project = project(7L, "Atenea Core");
        when(coreProjectResolver.requireById(7L)).thenReturn(project);

        CoreInterpretationResult result = interpreter.interpret(new CreateCoreCommandRequest(
                "abre una sesion para Atenea Core",
                CoreChannel.TEXT,
                new CoreRequestContext(7L, null),
                null));

        assertEquals("Work on Atenea Core", result.proposal().parameters().get("title"));
    }

    @Test
    void interpretRecognizesAccentedSpanishOpenSessionRequests() {
        ProjectEntity project = project(7L, "Atenea Core");
        when(coreProjectResolver.requireById(7L)).thenReturn(project);

        CoreInterpretationResult result = interpreter.interpret(new CreateCoreCommandRequest(
                "abre una sesión para Atenea Core",
                CoreChannel.TEXT,
                new CoreRequestContext(7L, null),
                null));

        assertEquals("create_work_session", result.proposal().capability());
        assertEquals("Work on Atenea Core", result.proposal().parameters().get("title"));
    }

    @Test
    void interpretTreatsProjectStatusAsProjectOverviewInsteadOfPortfolioOverview() {
        ProjectEntity project = project(9L, "pruebas-inicial");
        when(coreProjectResolver.resolveFromInputOrActive("estado del proyecto pruebas inicial", null)).thenReturn(project);

        CoreInterpretationResult result = interpreter.interpret(new CreateCoreCommandRequest(
                "estado del proyecto pruebas inicial",
                CoreChannel.TEXT,
                null,
                null));

        assertEquals("get_project_overview", result.proposal().capability());
        assertEquals(Map.of("projectId", 9L), result.proposal().parameters());
    }

    @Test
    void interpretKeepsPluralProjectsQueryAsPortfolioOverview() {
        CoreInterpretationResult result = interpreter.interpret(new CreateCoreCommandRequest(
                "dime el estado de los proyectos",
                CoreChannel.TEXT,
                null,
                null));

        assertEquals("list_projects_overview", result.proposal().capability());
    }

    @Test
    void interpretRoutesDevelopmentProgressQuestionsToSessionSummary() {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(44L);
        session.setProject(project(9L, "pruebas-inicial"));
        when(coreWorkSessionResolver.resolveActiveSession(
                "Del proyecto Prueba Inicial, ¿me puedes decir en qué punto estamos?",
                null,
                null)).thenReturn(session);

        CoreInterpretationResult result = interpreter.interpret(new CreateCoreCommandRequest(
                "Del proyecto Prueba Inicial, ¿me puedes decir en qué punto estamos?",
                CoreChannel.TEXT,
                null,
                null));

        assertEquals("get_session_summary", result.proposal().capability());
        assertEquals(Map.of("projectId", 9L, "workSessionId", 44L), result.proposal().parameters());
    }

    @Test
    void interpretRoutesDeliverableApprovalToCoreCapability() {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(44L);
        session.setProject(project(9L, "pruebas-inicial"));
        when(coreWorkSessionResolver.resolveActiveSession("aprueba el deliverable 301", 44L, 9L))
                .thenReturn(session);

        CoreInterpretationResult result = interpreter.interpret(new CreateCoreCommandRequest(
                "aprueba el deliverable 301",
                CoreChannel.TEXT,
                new CoreRequestContext(9L, 44L),
                null));

        assertEquals("approve_session_deliverable", result.proposal().capability());
        assertEquals(
                Map.of("projectId", 9L, "workSessionId", 44L, "deliverableId", 301L),
                result.proposal().parameters());
    }

    @Test
    void interpretRoutesBillingReferenceRequestToCoreCapability() {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(44L);
        session.setProject(project(9L, "pruebas-inicial"));
        when(coreWorkSessionResolver.resolveActiveSession(
                "marca el deliverable 501 como facturado con referencia INV-1",
                44L,
                9L)).thenReturn(session);

        CoreInterpretationResult result = interpreter.interpret(new CreateCoreCommandRequest(
                "marca el deliverable 501 como facturado con referencia INV-1",
                CoreChannel.TEXT,
                new CoreRequestContext(9L, 44L),
                null));

        assertEquals("mark_price_estimate_billed", result.proposal().capability());
        assertEquals(
                Map.of(
                        "projectId", 9L,
                        "workSessionId", 44L,
                        "deliverableId", 501L,
                        "billingReference", "INV-1"),
                result.proposal().parameters());
    }

    private static ProjectEntity project(Long id, String name) {
        ProjectEntity entity = new ProjectEntity();
        entity.setId(id);
        entity.setName(name);
        return entity;
    }
}
