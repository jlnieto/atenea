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
                coreLlmProperties);
    }

    @Test
    void interpretResolvesContinueWorkSessionFromLlmOutput() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of(project(7L, "Atenea")));
        when(workSessionRepository.findByStatusInOrderByLastActivityAtDesc(any())).thenReturn(List.of(activeSession()));
        when(codexAppServerClient.execute(any(CodexAppServerExecutionRequest.class))).thenReturn(
                new CodexAppServerExecutionResult(
                        "thread-1",
                        "turn-1",
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        """
                        {"intent":"CONTINUE_WORK_SESSION","domain":"DEVELOPMENT","capability":"continue_work_session","parameters":{"workSessionId":44},"confidence":0.88}
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
        assertTrue(captor.getValue().prompt().contains("\"projectId\":7"));
    }

    @Test
    void interpretSupportsCodeFencedJsonAndCreatesWorkSessionProposal() throws Exception {
        when(projectRepository.findAll()).thenReturn(List.of(project(7L, "Atenea")));
        when(workSessionRepository.findByStatusInOrderByLastActivityAtDesc(any())).thenReturn(List.of());
        when(codexAppServerClient.execute(any(CodexAppServerExecutionRequest.class))).thenReturn(
                new CodexAppServerExecutionResult(
                        "thread-1",
                        "turn-1",
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        """
                        ```json
                        {"intent":"CREATE_WORK_SESSION","domain":"DEVELOPMENT","capability":"create_work_session","parameters":{"projectId":7,"title":"Preparar Atenea Core"},"confidence":0.74}
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
