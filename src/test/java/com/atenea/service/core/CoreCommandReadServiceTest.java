package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.atenea.api.core.CoreCommandDetailResponse;
import com.atenea.api.core.CoreCommandListResponse;
import com.atenea.persistence.core.CoreChannel;
import com.atenea.persistence.core.CoreCommandEntity;
import com.atenea.persistence.core.CoreCommandRepository;
import com.atenea.persistence.core.CoreCommandStatus;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreInterpreterSource;
import com.atenea.persistence.core.CoreRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class CoreCommandReadServiceTest {

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
    }

    @Test
    void getCommandsFiltersAndMapsPersistedTelemetry() {
        when(coreCommandRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))).thenReturn(List.of(
                command(101L, CoreCommandStatus.SUCCEEDED, CoreInterpreterSource.LLM, "continua atenea"),
                command(102L, CoreCommandStatus.REJECTED, CoreInterpreterSource.DETERMINISTIC, "otro comando")));

        CoreCommandListResponse response = coreCommandService.getCommands(
                CoreCommandStatus.SUCCEEDED,
                CoreDomain.DEVELOPMENT,
                CoreInterpreterSource.LLM,
                "atenea");

        assertEquals(1, response.items().size());
        assertEquals(101L, response.items().getFirst().commandId());
        assertEquals(CoreInterpreterSource.LLM, response.items().getFirst().interpretation().source());
    }

    @Test
    void getCommandReturnsParsedJsonDetail() {
        CoreCommandEntity command = command(101L, CoreCommandStatus.SUCCEEDED, CoreInterpreterSource.LLM, "continua atenea");
        command.setRequestContextJson("{\"projectId\":7}");
        command.setParametersJson("{\"workSessionId\":44}");
        command.setInterpretedIntentJson("{\"capability\":\"continue_work_session\"}");
        when(coreCommandRepository.findById(101L)).thenReturn(Optional.of(command));

        CoreCommandDetailResponse response = coreCommandService.getCommand(101L);

        assertEquals(101L, response.commandId());
        assertEquals(7, response.requestContext().get("projectId").asInt());
        assertEquals(44, response.parameters().get("workSessionId").asInt());
        assertEquals("continue_work_session", response.interpretedIntent().get("capability").asText());
    }

    @Test
    void getCommandThrowsWhenCommandDoesNotExist() {
        when(coreCommandRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(CoreCommandNotFoundException.class, () -> coreCommandService.getCommand(999L));
    }

    private static CoreCommandEntity command(
            Long id,
            CoreCommandStatus status,
            CoreInterpreterSource source,
            String rawInput
    ) {
        CoreCommandEntity command = new CoreCommandEntity();
        command.setId(id);
        command.setRawInput(rawInput);
        command.setChannel(CoreChannel.TEXT);
        command.setStatus(status);
        command.setDomain(CoreDomain.DEVELOPMENT);
        command.setIntent("CONTINUE_WORK_SESSION");
        command.setCapability("continue_work_session");
        command.setRiskLevel(CoreRiskLevel.READ);
        command.setRequiresConfirmation(false);
        command.setConfirmed(false);
        command.setConfidence(BigDecimal.valueOf(0.88));
        command.setInterpreterSource(source);
        command.setInterpreterDetail("llm_structured_classification");
        command.setResultSummary("Resolved conversation");
        command.setOperatorMessage("ok");
        command.setSpeakableMessage("ok");
        command.setCreatedAt(Instant.parse("2026-03-30T20:00:00Z"));
        command.setFinishedAt(Instant.parse("2026-03-30T20:00:01Z"));
        return command;
    }
}
