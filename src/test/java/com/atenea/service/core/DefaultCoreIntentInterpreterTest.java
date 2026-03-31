package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atenea.api.core.CoreRequestContext;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreChannel;
import com.atenea.persistence.project.ProjectEntity;
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

    private static ProjectEntity project(Long id, String name) {
        ProjectEntity entity = new ProjectEntity();
        entity.setId(id);
        entity.setName(name);
        return entity;
    }
}
