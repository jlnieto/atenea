package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.api.core.CoreRequestContext;
import com.atenea.persistence.core.CoreChannel;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreInterpreterSource;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HybridCoreIntentInterpreterTest {

    @Mock
    private DefaultCoreIntentInterpreter defaultCoreIntentInterpreter;

    @Mock
    private LlmCoreIntentInterpreter llmCoreIntentInterpreter;

    private CoreLlmProperties coreLlmProperties;
    private HybridCoreIntentInterpreter interpreter;

    @BeforeEach
    void setUp() {
        coreLlmProperties = new CoreLlmProperties();
        interpreter = new HybridCoreIntentInterpreter(
                defaultCoreIntentInterpreter,
                llmCoreIntentInterpreter,
                coreLlmProperties);
    }

    @Test
    void interpretUsesDeterministicInterpreterWhenExplicitWorkSessionContextExists() {
        CreateCoreCommandRequest request = new CreateCoreCommandRequest(
                "sigue con esto",
                CoreChannel.TEXT,
                new CoreRequestContext(7L, 44L),
                null);
        CoreInterpretationResult proposal = new CoreInterpretationResult(
                new CoreIntentProposal(
                        "CONTINUE_WORK_SESSION",
                        CoreDomain.DEVELOPMENT,
                        "continue_work_session",
                        Map.of("workSessionId", 44L, "message", "sigue con esto"),
                        BigDecimal.ONE),
                CoreInterpreterSource.DETERMINISTIC,
                "explicit_work_session_context");
        when(defaultCoreIntentInterpreter.interpret(request)).thenReturn(proposal);

        CoreInterpretationResult result = interpreter.interpret(request);

        assertEquals(proposal, result);
        verify(defaultCoreIntentInterpreter).interpret(request);
        verifyNoInteractions(llmCoreIntentInterpreter);
    }

    @Test
    void interpretUsesLlmWhenOnlyProjectContextExists() {
        coreLlmProperties.setEnabled(true);
        CreateCoreCommandRequest request = new CreateCoreCommandRequest(
                "estado del proyecto pruebas inicial",
                CoreChannel.TEXT,
                new CoreRequestContext(7L, null),
                null);
        CoreInterpretationResult proposal = new CoreInterpretationResult(
                new CoreIntentProposal(
                        "GET_PROJECT_OVERVIEW",
                        CoreDomain.DEVELOPMENT,
                        "get_project_overview",
                        Map.of("projectId", 7L),
                        BigDecimal.valueOf(0.84)),
                CoreInterpreterSource.LLM,
                "llm_structured_classification");
        when(llmCoreIntentInterpreter.interpret(request)).thenReturn(proposal);

        CoreInterpretationResult result = interpreter.interpret(request);

        assertEquals(proposal, result);
        verify(llmCoreIntentInterpreter).interpret(request);
        verifyNoInteractions(defaultCoreIntentInterpreter);
    }

    @Test
    void interpretUsesLlmWhenEnabledAndNoExplicitContextExists() {
        coreLlmProperties.setEnabled(true);
        CreateCoreCommandRequest request = new CreateCoreCommandRequest(
                "continua con la sesion de Atenea",
                CoreChannel.TEXT,
                null,
                null);
        CoreInterpretationResult proposal = new CoreInterpretationResult(
                new CoreIntentProposal(
                        "CONTINUE_WORK_SESSION",
                        CoreDomain.DEVELOPMENT,
                        "continue_work_session",
                        Map.of("workSessionId", 44L, "message", "continua con la sesion de Atenea"),
                        BigDecimal.valueOf(0.88)),
                CoreInterpreterSource.LLM,
                "llm_structured_classification");
        when(llmCoreIntentInterpreter.interpret(request)).thenReturn(proposal);

        CoreInterpretationResult result = interpreter.interpret(request);

        assertEquals(proposal, result);
        verify(llmCoreIntentInterpreter).interpret(request);
        verifyNoInteractions(defaultCoreIntentInterpreter);
    }

    @Test
    void interpretFallsBackToDeterministicInterpreterWhenLlmFails() {
        coreLlmProperties.setEnabled(true);
        CreateCoreCommandRequest request = new CreateCoreCommandRequest(
                "haz algo",
                CoreChannel.TEXT,
                null,
                null);
        CoreInterpretationResult fallback = new CoreInterpretationResult(
                new CoreIntentProposal(
                        "CREATE_WORK_SESSION",
                        CoreDomain.DEVELOPMENT,
                        "create_work_session",
                        Map.of("projectId", 7L, "title", "haz algo"),
                        BigDecimal.valueOf(0.90)),
                CoreInterpreterSource.DETERMINISTIC,
                "explicit_project_context");
        when(llmCoreIntentInterpreter.interpret(request)).thenThrow(new IllegalStateException("llm down"));
        when(defaultCoreIntentInterpreter.interpret(request)).thenReturn(fallback);

        CoreInterpretationResult result = interpreter.interpret(request);

        assertEquals(fallback.proposal(), result.proposal());
        assertEquals(CoreInterpreterSource.DETERMINISTIC_FALLBACK, result.source());
        verify(llmCoreIntentInterpreter).interpret(request);
        verify(defaultCoreIntentInterpreter).interpret(request);
    }
}
