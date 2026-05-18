package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.core.CoreCommandResponse;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.api.core.CoreRequestContext;
import com.atenea.persistence.core.CoreChannel;
import com.atenea.persistence.core.CoreCommandEntity;
import com.atenea.persistence.core.CoreCommandRepository;
import com.atenea.persistence.core.CoreCommandStatus;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreInterpreterSource;
import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreRiskLevel;
import com.atenea.persistence.core.CoreTargetType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreCommandServiceTest {

    @Mock
    private CoreCommandRepository coreCommandRepository;

    @Mock
    private CoreDomainRouter coreDomainRouter;

    @Mock
    private CoreOperatorContextService coreOperatorContextService;

    @Mock
    private CoreIntentInterpreter coreIntentInterpreter;

    @Mock
    private CoreCommandEventService coreCommandEventService;

    private CoreCommandService coreCommandService;

    @BeforeEach
    void setUp() {
        when(coreCommandRepository.save(any(CoreCommandEntity.class))).thenAnswer(invocation -> {
            CoreCommandEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(101L);
            }
            return entity;
        });
        lenient().when(coreOperatorContextService.applyActiveContext(any(CreateCoreCommandRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(coreOperatorContextService.resolveOperatorKey(any()))
                .thenReturn(CoreOperatorContextService.DEFAULT_OPERATOR_KEY);

        coreCommandService = new CoreCommandService(
                coreCommandRepository,
                coreIntentInterpreter,
                new CoreCapabilityRegistry(),
                coreDomainRouter,
                coreOperatorContextService,
                coreCommandEventService,
                new ObjectMapper());
    }

    @Test
    void createCommandPersistsSucceededCommandForProjectContext() {
        when(coreIntentInterpreter.interpret(any(CreateCoreCommandRequest.class))).thenReturn(new CoreInterpretationResult(
                new CoreIntentProposal(
                        "CREATE_WORK_SESSION",
                        CoreDomain.DEVELOPMENT,
                        "create_work_session",
                        Map.of("projectId", 7L, "title", "Arranca una nueva sesion"),
                        BigDecimal.valueOf(0.90)),
                CoreInterpreterSource.DETERMINISTIC,
                "explicit_project_context"));
        when(coreDomainRouter.execute(any(CoreIntentEnvelope.class), any(CoreExecutionContext.class)))
                .thenReturn(new CoreCommandExecutionResult(
                        CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                        CoreTargetType.WORK_SESSION,
                        44L,
                        Map.of("created", true),
                        "Resolved WorkSession conversation view for project 7",
                        "He creado una WorkSession y la conversación ya está lista.",
                        "He creado una WorkSession y la conversación ya está lista."));

        CoreCommandResponse response = coreCommandService.createCommand(new CreateCoreCommandRequest(
                "Arranca una nueva sesion",
                CoreChannel.TEXT,
                new CoreRequestContext(7L, null),
                null));

        assertEquals(101L, response.commandId());
        assertEquals(CoreCommandStatus.SUCCEEDED, response.status());
        assertEquals(CoreInterpreterSource.DETERMINISTIC, response.interpretation().source());
        assertEquals("create_work_session", response.intent().capability());
        assertEquals(44L, response.result().targetId());

        ArgumentCaptor<CoreCommandEntity> captor = ArgumentCaptor.forClass(CoreCommandEntity.class);
        verify(coreCommandRepository, atLeast(3)).save(captor.capture());
        List<CoreCommandEntity> savedEntities = captor.getAllValues();
        CoreCommandEntity finalState = savedEntities.get(savedEntities.size() - 1);
        assertEquals(CoreCommandStatus.SUCCEEDED, finalState.getStatus());
        assertEquals(CoreDomain.DEVELOPMENT, finalState.getDomain());
        assertEquals("CREATE_WORK_SESSION", finalState.getIntent());
        assertEquals("create_work_session", finalState.getCapability());
        assertEquals(CoreRiskLevel.READ, finalState.getRiskLevel());
        assertEquals(CoreResultType.WORK_SESSION_CONVERSATION_VIEW, finalState.getResultType());
        assertEquals(CoreTargetType.WORK_SESSION, finalState.getTargetType());
        assertEquals(44L, finalState.getTargetId());
        assertEquals(CoreInterpreterSource.DETERMINISTIC, finalState.getInterpreterSource());
        assertNotNull(finalState.getInterpretedIntentJson());
        assertNotNull(finalState.getParametersJson());
    }

    @Test
    void createCommandReturnsConfirmationTokenWhenCapabilityRequiresConfirmation() {
        CoreIntentInterpreter interpreter = request -> new CoreInterpretationResult(
                new CoreIntentProposal(
                        "RESTART_SERVICE",
                        CoreDomain.OPERATIONS,
                        "restart_service",
                        Map.of("projectId", 7L),
                        BigDecimal.valueOf(0.70)),
                CoreInterpreterSource.LLM,
                "llm_structured_classification");
        CoreCapabilityRegistry registry = org.mockito.Mockito.mock(CoreCapabilityRegistry.class);
        when(registry.requireEnabled(CoreDomain.OPERATIONS, "restart_service")).thenReturn(
                new CapabilityDefinition(
                        CoreDomain.OPERATIONS,
                        "restart_service",
                        CoreRiskLevel.SAFE_WRITE,
                        true,
                        true));
        CoreCommandService confirmationService = new CoreCommandService(
                coreCommandRepository,
                interpreter,
                registry,
                coreDomainRouter,
                coreOperatorContextService,
                coreCommandEventService,
                new ObjectMapper());

        CoreCommandResponse response = confirmationService.createCommand(new CreateCoreCommandRequest(
                "reinicia apache",
                CoreChannel.TEXT,
                new CoreRequestContext(7L, null),
                null));

        assertEquals(CoreCommandStatus.NEEDS_CONFIRMATION, response.status());
        assertNotNull(response.confirmation());
        assertNotNull(response.confirmation().confirmationToken());
    }

    @Test
    void createCommandUsesProjectDatabaseRefreshConfirmationWording() {
        when(coreIntentInterpreter.interpret(any(CreateCoreCommandRequest.class))).thenReturn(new CoreInterpretationResult(
                new CoreIntentProposal(
                        "REFRESH_PROJECT_DATABASE",
                        CoreDomain.DEVELOPMENT,
                        "refresh_project_database",
                        Map.of("projectId", 9L, "projectName", "Fomasys"),
                        BigDecimal.valueOf(0.96)),
                CoreInterpreterSource.DETERMINISTIC,
                "explicit_project_database_refresh_request"));

        CoreCommandResponse response = coreCommandService.createCommand(new CreateCoreCommandRequest(
                "Atenea, actualiza bd",
                CoreChannel.VOICE,
                new CoreRequestContext(9L, 44L),
                null));

        assertEquals(CoreCommandStatus.NEEDS_CONFIRMATION, response.status());
        assertNotNull(response.confirmation());
        assertEquals(
                "Se va a reemplazar la base de datos local de Fomasys por la que hay en producción. Confirma para continuar.",
                response.operatorMessage());
        assertEquals(response.operatorMessage(), response.confirmation().message());
    }

    @Test
    void createCommandExecutesGenerateSessionDeliverableWithoutConfirmation() {
        when(coreIntentInterpreter.interpret(any(CreateCoreCommandRequest.class))).thenReturn(new CoreInterpretationResult(
                new CoreIntentProposal(
                        "GENERATE_SESSION_DELIVERABLE",
                        CoreDomain.DEVELOPMENT,
                        "generate_session_deliverable",
                        Map.of("projectId", 7L, "workSessionId", 44L, "deliverableType", "WORK_TICKET"),
                        BigDecimal.valueOf(0.92)),
                CoreInterpreterSource.DETERMINISTIC,
                "deliverable_generation_request"));
        when(coreDomainRouter.execute(any(CoreIntentEnvelope.class), any(CoreExecutionContext.class)))
                .thenReturn(new CoreCommandExecutionResult(
                        CoreResultType.SESSION_DELIVERABLE,
                        CoreTargetType.SESSION_DELIVERABLE,
                        301L,
                        Map.of("id", 301L),
                        "Generated WORK_TICKET deliverable for WorkSession 44",
                        "He generado correctamente el entregable solicitado.",
                        "He generado correctamente el entregable solicitado."));

        CoreCommandResponse response = coreCommandService.createCommand(new CreateCoreCommandRequest(
                "genera ticket de trabajo",
                CoreChannel.TEXT,
                new CoreRequestContext(7L, 44L),
                null));

        assertEquals(CoreCommandStatus.SUCCEEDED, response.status());
        assertEquals("generate_session_deliverable", response.intent().capability());
        assertEquals(CoreResultType.SESSION_DELIVERABLE, response.result().type());
        assertEquals(CoreTargetType.SESSION_DELIVERABLE, response.result().targetType());
        assertEquals(301L, response.result().targetId());
    }

    @Test
    void createCommandMarksRejectedWhenIntentCannotBeDetermined() {
        when(coreIntentInterpreter.interpret(any(CreateCoreCommandRequest.class))).thenThrow(new CoreUnknownIntentException(
                "Atenea Core could not determine a supported development intent for the current request"));
        CoreUnknownIntentException exception = assertThrows(CoreUnknownIntentException.class, () -> coreCommandService.createCommand(
                new CreateCoreCommandRequest(
                        "haz algo",
                        CoreChannel.TEXT,
                        null,
                        null)));

        assertEquals(
                "Atenea Core could not determine a supported development intent for the current request",
                exception.getMessage());

        ArgumentCaptor<CoreCommandEntity> captor = ArgumentCaptor.forClass(CoreCommandEntity.class);
        verify(coreCommandRepository, atLeast(2)).save(captor.capture());
        List<CoreCommandEntity> savedEntities = captor.getAllValues();
        CoreCommandEntity finalState = savedEntities.get(savedEntities.size() - 1);
        assertEquals(CoreCommandStatus.REJECTED, finalState.getStatus());
        assertEquals("UNKNOWN_INTENT", finalState.getErrorCode());
    }

    @Test
    void createCommandReturnsNeedsClarificationWhenInterpreterRequiresIt() {
        when(coreIntentInterpreter.interpret(any(CreateCoreCommandRequest.class))).thenThrow(
                new CoreClarificationRequiredException(
                        new CoreClarification(
                                "More than one project matches the current request. Please clarify the project.",
                                List.of(
                                        new CoreClarificationOption("PROJECT", 7L, "Atenea"),
                                        new CoreClarificationOption("PROJECT", 8L, "Atenea Mobile")))));

        CoreCommandResponse response = coreCommandService.createCommand(new CreateCoreCommandRequest(
                "vamos a trabajar en atenea",
                CoreChannel.TEXT,
                null,
                null));

        assertEquals(CoreCommandStatus.NEEDS_CLARIFICATION, response.status());
        assertNotNull(response.clarification());
        assertEquals(2, response.clarification().options().size());
    }
}
