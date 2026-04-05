package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.codexappserver.CodexAppServerClient;
import com.atenea.codexappserver.CodexAppServerExecutionRequest;
import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.codexappserver.CodexAppServerProperties;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.core.CoreInterpreterSource;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmCoreIntentInterpreterTest {

    @Mock
    private CodexAppServerClient codexAppServerClient;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private WorkSessionRepository workSessionRepository;

    private LlmCoreIntentInterpreter interpreter;

    @BeforeEach
    void setUp() {
        CoreLlmProperties coreLlmProperties = new CoreLlmProperties();
        coreLlmProperties.setEnabled(true);
        CodexAppServerProperties codexAppServerProperties = new CodexAppServerProperties();
        codexAppServerProperties.setCwd("/workspace/repos/internal/atenea");
        interpreter = new LlmCoreIntentInterpreter(
                codexAppServerClient,
                codexAppServerProperties,
                projectRepository,
                workSessionRepository,
                new WorkspaceRepositoryPathValidator("/workspace/repos"),
                new ObjectMapper(),
                coreLlmProperties,
                new CoreCapabilityRegistry(),
                new CoreCapabilityArgumentResolver(
                        new CoreProjectResolver(projectRepository),
                        new CoreWorkSessionResolver(workSessionRepository)));
    }

    @Test
    void interpretResolvesContinueWorkSessionFromLlmOutput() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of(project(7L, "Atenea")));
        when(workSessionRepository.findByStatusInOrderByLastActivityAtDesc(any())).thenReturn(List.of(activeSession()));
        when(workSessionRepository.findWithProjectById(44L)).thenReturn(Optional.of(activeSession()));
        when(codexAppServerClient.execute(any(CodexAppServerExecutionRequest.class))).thenReturn(
                new CodexAppServerExecutionResult(
                        "thread-1",
                        "turn-1",
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        """
                        {"domain":"DEVELOPMENT","capability":"continue_work_session","confidence":0.88,"arguments":{"workSessionId":44},"resolutionHints":{},"needsClarification":false,"missing":[],"reasoning":"Execution request for the active session."}
                        """,
                        null,
                        null,
                        null));

        CoreInterpretationResult interpretation = interpreter.interpret(new CreateCoreCommandRequest(
                "continua con la sesion de Atenea",
                com.atenea.persistence.core.CoreChannel.TEXT,
                null,
                null));
        CoreIntentProposal proposal = interpretation.proposal();

        assertEquals("CONTINUE_WORK_SESSION", proposal.intent());
        assertEquals("continue_work_session", proposal.capability());
        assertEquals(44L, proposal.parameters().get("workSessionId"));
        assertEquals("continua con la sesion de Atenea", proposal.parameters().get("message"));
        assertEquals(CoreInterpreterSource.LLM, interpretation.source());

        ArgumentCaptor<CodexAppServerExecutionRequest> captor =
                ArgumentCaptor.forClass(CodexAppServerExecutionRequest.class);
        verify(codexAppServerClient).execute(captor.capture());
        assertEquals("/workspace/repos/internal/atenea", captor.getValue().repoPath());
        assertTrue(captor.getValue().prompt().contains("\"activeWorkSessions\""));
        assertTrue(captor.getValue().prompt().contains("\"capabilityCatalog\""));
        assertTrue(captor.getValue().prompt().contains("\"projectId\":7"));
    }

    @Test
    void interpretSupportsCodeFencedJsonAndCreatesWorkSessionProposal() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of(project(7L, "Atenea")));
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project(7L, "Atenea")));
        when(workSessionRepository.findByStatusInOrderByLastActivityAtDesc(any())).thenReturn(List.of());
        when(codexAppServerClient.execute(any(CodexAppServerExecutionRequest.class))).thenReturn(
                new CodexAppServerExecutionResult(
                        "thread-1",
                        "turn-1",
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        """
                        ```json
                        {"domain":"DEVELOPMENT","capability":"create_work_session","confidence":0.74,"arguments":{"projectId":7,"title":"Preparar Atenea Core"},"resolutionHints":{},"needsClarification":false,"missing":[],"reasoning":"Open a work session for one project."}
                        ```
                        """,
                        null,
                        null,
                        null));

        CoreInterpretationResult interpretation = interpreter.interpret(new CreateCoreCommandRequest(
                "abre una sesion para Atenea Core",
                com.atenea.persistence.core.CoreChannel.TEXT,
                null,
                null));
        CoreIntentProposal proposal = interpretation.proposal();

        assertEquals("create_work_session", proposal.capability());
        assertEquals(7L, proposal.parameters().get("projectId"));
        assertEquals("Preparar Atenea Core", proposal.parameters().get("title"));
        assertEquals(CoreInterpreterSource.LLM, interpretation.source());
    }

    @Test
    void interpretSupportsPortfolioOverviewClassification() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of(project(7L, "Atenea"), project(8L, "Hermes")));
        when(workSessionRepository.findByStatusInOrderByLastActivityAtDesc(any())).thenReturn(List.of());
        when(codexAppServerClient.execute(any(CodexAppServerExecutionRequest.class))).thenReturn(
                new CodexAppServerExecutionResult(
                        "thread-1",
                        "turn-1",
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        """
                        {"domain":"DEVELOPMENT","capability":"list_projects_overview","confidence":0.81,"arguments":{},"resolutionHints":{},"needsClarification":false,"missing":[],"reasoning":"Plural portfolio overview request."}
                        """,
                        null,
                        null,
                        null));

        CoreInterpretationResult interpretation = interpreter.interpret(new CreateCoreCommandRequest(
                "dime el estado de los proyectos",
                com.atenea.persistence.core.CoreChannel.TEXT,
                null,
                null));

        assertEquals("list_projects_overview", interpretation.proposal().capability());
        assertEquals(CoreInterpreterSource.LLM, interpretation.source());
    }

    @Test
    void interpretSupportsProjectOverviewClassification() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of(project(7L, "pruebas-inicial"), project(8L, "Hermes")));
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project(7L, "pruebas-inicial")));
        when(workSessionRepository.findByStatusInOrderByLastActivityAtDesc(any())).thenReturn(List.of());
        when(codexAppServerClient.execute(any(CodexAppServerExecutionRequest.class))).thenReturn(
                new CodexAppServerExecutionResult(
                        "thread-1",
                        "turn-1",
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        """
                        {"domain":"DEVELOPMENT","capability":"get_project_overview","confidence":0.84,"arguments":{"projectId":7},"resolutionHints":{},"needsClarification":false,"missing":[],"reasoning":"Administrative status for one project."}
                        """,
                        null,
                        null,
                        null));

        CoreInterpretationResult interpretation = interpreter.interpret(new CreateCoreCommandRequest(
                "dime el estado del proyecto pruebas inicial",
                com.atenea.persistence.core.CoreChannel.TEXT,
                null,
                null));

        assertEquals("get_project_overview", interpretation.proposal().capability());
        assertEquals(7L, interpretation.proposal().parameters().get("projectId"));
        assertEquals(CoreInterpreterSource.LLM, interpretation.source());
    }

    @Test
    void interpretPrefersExplicitProjectEvenWhenPromptContainsPortfolioWording() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of(project(7L, "pruebas-inicial"), project(8L, "Hermes")));
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project(7L, "pruebas-inicial")));
        when(workSessionRepository.findByStatusInOrderByLastActivityAtDesc(any())).thenReturn(List.of());
        when(codexAppServerClient.execute(any(CodexAppServerExecutionRequest.class))).thenReturn(
                new CodexAppServerExecutionResult(
                        "thread-1",
                        "turn-1",
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        """
                        {"domain":"DEVELOPMENT","capability":"get_project_overview","confidence":0.96,"arguments":{"projectId":7,"workSessionId":0},"resolutionHints":{"projectName":"pruebas inicial"},"needsClarification":false,"missing":[],"reasoning":"A specific project is explicitly named."}
                        """,
                        null,
                        null,
                        null));

        CoreInterpretationResult interpretation = interpreter.interpret(new CreateCoreCommandRequest(
                "me puedes decir el estado de todos los proyectos, el proyecto pruebas inicial",
                com.atenea.persistence.core.CoreChannel.TEXT,
                null,
                null));

        assertEquals("get_project_overview", interpretation.proposal().capability());
        assertEquals(7L, interpretation.proposal().parameters().get("projectId"));
        assertTrue(!interpretation.proposal().parameters().containsKey("workSessionId"));
    }

    @Test
    void interpretSupportsSessionSummaryClassificationForDevelopmentProgress() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of(project(7L, "pruebas-inicial"), project(8L, "Hermes")));
        when(workSessionRepository.findByStatusInOrderByLastActivityAtDesc(any())).thenReturn(List.of(activeSession()));
        when(workSessionRepository.findByProjectIdOrderByLastActivityAtDesc(7L)).thenReturn(List.of(activeSession()));
        when(codexAppServerClient.execute(any(CodexAppServerExecutionRequest.class))).thenReturn(
                new CodexAppServerExecutionResult(
                        "thread-1",
                        "turn-1",
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        """
                        {"domain":"DEVELOPMENT","capability":"get_session_summary","confidence":0.91,"arguments":{"projectId":7},"resolutionHints":{"projectName":"pruebas inicial"},"needsClarification":false,"missing":[],"reasoning":"The operator asks for development progress."}
                        """,
                        null,
                        null,
                        null));

        CoreInterpretationResult interpretation = interpreter.interpret(new CreateCoreCommandRequest(
                "del proyecto pruebas inicial, dime en que punto estamos",
                com.atenea.persistence.core.CoreChannel.TEXT,
                null,
                null));

        assertEquals("get_session_summary", interpretation.proposal().capability());
        assertEquals(7L, interpretation.proposal().parameters().get("projectId"));
        assertEquals(44L, interpretation.proposal().parameters().get("workSessionId"));
        assertTrue(interpretation.detail().startsWith("llm_capability_router:hinted:"));
    }

    private static ProjectEntity project(Long id, String name) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setName(name);
        project.setDescription("repo interno");
        project.setRepoPath("/workspace/repos/internal/" + name.toLowerCase());
        project.setCreatedAt(Instant.parse("2026-03-30T10:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-30T10:00:00Z"));
        return project;
    }

    private static WorkSessionEntity activeSession() {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(44L);
        session.setProject(project(7L, "Atenea"));
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Core foundation");
        session.setBaseBranch("main");
        session.setWorkspaceBranch("atenea/session-44");
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(Instant.parse("2026-03-30T10:00:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-30T10:01:00Z"));
        session.setCreatedAt(Instant.parse("2026-03-30T10:00:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-30T10:01:00Z"));
        return session;
    }
}
