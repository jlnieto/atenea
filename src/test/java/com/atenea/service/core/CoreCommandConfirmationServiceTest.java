package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.core.ConfirmCoreCommandRequest;
import com.atenea.api.core.CoreCommandResponse;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreCommandConfirmationServiceTest {

    @Mock
    private CoreCommandRepository coreCommandRepository;

    @Mock
    private CoreIntentInterpreter coreIntentInterpreter;

    @Mock
    private CoreDomainRouter coreDomainRouter;

    @Mock
    private CoreOperatorContextService coreOperatorContextService;

    @Mock
    private CoreCommandEventService coreCommandEventService;

    private CoreCommandService coreCommandService;

    @BeforeEach
    void setUp() {
        coreCommandService = new CoreCommandService(
                coreCommandRepository,
                coreIntentInterpreter,
                new CoreCapabilityRegistry(),
                coreDomainRouter,
                coreOperatorContextService,
                coreCommandEventService,
                new ObjectMapper());
        lenient().when(coreOperatorContextService.resolveOperatorKey(any()))
                .thenReturn(CoreOperatorContextService.DEFAULT_OPERATOR_KEY);
    }

    @Test
    void confirmCommandExecutesPersistedPendingCommand() {
        when(coreCommandRepository.save(any(CoreCommandEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CoreCommandEntity pending = pendingCommand();
        when(coreCommandRepository.findById(101L)).thenReturn(Optional.of(pending));
        when(coreDomainRouter.execute(any(CoreIntentEnvelope.class), any(CoreExecutionContext.class)))
                .thenReturn(new CoreCommandExecutionResult(
                        CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                        CoreTargetType.WORK_SESSION,
                        44L,
                        Map.of("ok", true),
                        "Resolved conversation",
                        "The active WorkSession was continued successfully.",
                        "The active work session was continued successfully."));

        CoreCommandResponse response = coreCommandService.confirmCommand(
                101L,
                new ConfirmCoreCommandRequest("token-123"));

        assertEquals(CoreCommandStatus.SUCCEEDED, response.status());
        assertEquals(44L, response.result().targetId());

        ArgumentCaptor<CoreCommandEntity> captor = ArgumentCaptor.forClass(CoreCommandEntity.class);
        verify(coreCommandRepository, atLeast(2)).save(captor.capture());
        CoreCommandEntity finalState = captor.getAllValues().getLast();
        assertEquals(CoreCommandStatus.SUCCEEDED, finalState.getStatus());
        assertEquals(true, finalState.isConfirmed());
    }

    @Test
    void confirmCommandRejectsWrongToken() {
        when(coreCommandRepository.findById(101L)).thenReturn(Optional.of(pendingCommand()));

        assertThrows(
                CoreCommandConfirmationTokenMismatchException.class,
                () -> coreCommandService.confirmCommand(101L, new ConfirmCoreCommandRequest("wrong-token")));
    }

    @Test
    void confirmCommandRejectsWhenCommandIsNotPending() {
        CoreCommandEntity succeeded = pendingCommand();
        succeeded.setStatus(CoreCommandStatus.SUCCEEDED);
        when(coreCommandRepository.findById(101L)).thenReturn(Optional.of(succeeded));

        assertThrows(
                CoreCommandConfirmationNotPendingException.class,
                () -> coreCommandService.confirmCommand(101L, new ConfirmCoreCommandRequest("token-123")));
    }

    private CoreCommandEntity pendingCommand() {
        CoreCommandEntity command = new CoreCommandEntity();
        command.setId(101L);
        command.setRawInput("continua con la sesion");
        command.setChannel(CoreChannel.TEXT);
        command.setStatus(CoreCommandStatus.NEEDS_CONFIRMATION);
        command.setDomain(CoreDomain.DEVELOPMENT);
        command.setIntent("CONTINUE_WORK_SESSION");
        command.setCapability("continue_work_session");
        command.setRiskLevel(CoreRiskLevel.READ);
        command.setRequiresConfirmation(true);
        command.setConfirmationToken("token-123");
        command.setConfirmed(false);
        command.setConfidence(BigDecimal.valueOf(0.88));
        command.setRequestContextJson("{\"projectId\":7,\"workSessionId\":44}");
        command.setParametersJson("{\"projectId\":7,\"workSessionId\":44,\"message\":\"continua con la sesion\"}");
        command.setInterpretedIntentJson("{\"intent\":\"CONTINUE_WORK_SESSION\"}");
        command.setInterpreterSource(CoreInterpreterSource.LLM);
        command.setInterpreterDetail("llm_structured_classification");
        command.setSpeakableMessage("The active work session was continued successfully.");
        command.setCreatedAt(Instant.parse("2026-03-30T20:00:00Z"));
        command.setFinishedAt(Instant.parse("2026-03-30T20:00:01Z"));
        return command;
    }
}
