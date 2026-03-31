package com.atenea.service.core;

import com.atenea.api.core.CoreConfirmationRequest;
import com.atenea.api.core.CoreRequestContext;
import com.atenea.api.core.CoreVoiceCommandResponse;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreChannel;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CoreVoiceCommandService {

    private final CoreVoiceTranscriptionService coreVoiceTranscriptionService;
    private final CoreCommandService coreCommandService;

    public CoreVoiceCommandService(
            CoreVoiceTranscriptionService coreVoiceTranscriptionService,
            CoreCommandService coreCommandService
    ) {
        this.coreVoiceTranscriptionService = coreVoiceTranscriptionService;
        this.coreCommandService = coreCommandService;
    }

    public CoreVoiceCommandResponse createVoiceCommand(
            MultipartFile audio,
            Long projectId,
            Long workSessionId,
            String operatorKey
    ) {
        String transcript = coreVoiceTranscriptionService.transcribe(audio);
        return new CoreVoiceCommandResponse(
                transcript,
                coreCommandService.createCommand(new CreateCoreCommandRequest(
                        transcript,
                        CoreChannel.VOICE,
                        new CoreRequestContext(projectId, workSessionId, operatorKey),
                        new CoreConfirmationRequest(false, null)))
        );
    }
}
