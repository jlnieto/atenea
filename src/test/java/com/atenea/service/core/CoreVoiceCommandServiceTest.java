package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.core.CoreCommandResponse;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class CoreVoiceCommandServiceTest {

    @Mock
    private CoreVoiceTranscriptionService coreVoiceTranscriptionService;

    @Mock
    private CoreCommandService coreCommandService;

    @Test
    void createVoiceCommandTranscribesAndDelegatesToCoreCommandService() {
        CoreVoiceCommandService service = new CoreVoiceCommandService(
                coreVoiceTranscriptionService,
                coreCommandService);
        MockMultipartFile audio = new MockMultipartFile(
                "audio",
                "voice-command.m4a",
                "audio/mp4",
                "fake-audio".getBytes());
        when(coreVoiceTranscriptionService.transcribe(audio)).thenReturn("continua con la sesion");
        CoreCommandResponse commandResponse = new CoreCommandResponse(
                101L,
                null,
                null,
                null,
                null,
                null,
                null,
                "ok",
                "ok");
        when(coreCommandService.createCommand(new CreateCoreCommandRequest(
                "continua con la sesion",
                CoreChannel.VOICE,
                new com.atenea.api.core.CoreRequestContext(7L, 12L, "operator@atenea.local"),
                new com.atenea.api.core.CoreConfirmationRequest(false, null))))
                .thenReturn(commandResponse);

        var response = service.createVoiceCommand(audio, 7L, 12L, "operator@atenea.local");

        assertEquals("continua con la sesion", response.transcript());
        assertEquals(commandResponse, response.command());
        verify(coreVoiceTranscriptionService).transcribe(audio);
    }
}
