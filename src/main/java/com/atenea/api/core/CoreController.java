package com.atenea.api.core;

import com.atenea.service.core.CoreCommandService;
import com.atenea.service.core.CoreVoiceCommandService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.atenea.persistence.core.CoreCommandStatus;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreInterpreterSource;
import com.atenea.service.core.CoreStreamService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/core")
public class CoreController {

    private final CoreCommandService coreCommandService;
    private final CoreVoiceCommandService coreVoiceCommandService;
    private final CoreStreamService coreStreamService;

    public CoreController(
            CoreCommandService coreCommandService,
            CoreVoiceCommandService coreVoiceCommandService,
            CoreStreamService coreStreamService
    ) {
        this.coreCommandService = coreCommandService;
        this.coreVoiceCommandService = coreVoiceCommandService;
        this.coreStreamService = coreStreamService;
    }

    @PostMapping("/commands")
    @ResponseStatus(HttpStatus.CREATED)
    public CoreCommandResponse createCommand(@Valid @RequestBody CreateCoreCommandRequest request) {
        return coreCommandService.createCommand(request);
    }

    @PostMapping("/commands/{commandId}/confirm")
    public CoreCommandResponse confirmCommand(
            @PathVariable Long commandId,
            @Valid @RequestBody ConfirmCoreCommandRequest request
    ) {
        return coreCommandService.confirmCommand(commandId, request);
    }

    @PostMapping(value = "/voice/commands", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CoreVoiceCommandResponse createVoiceCommand(
            @RequestPart("audio") MultipartFile audio,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workSessionId,
            @RequestParam(required = false) String operatorKey
    ) {
        return coreVoiceCommandService.createVoiceCommand(audio, projectId, workSessionId, operatorKey);
    }

    @GetMapping("/commands")
    public CoreCommandListResponse getCommands(
            @RequestParam(required = false) CoreCommandStatus status,
            @RequestParam(required = false) CoreDomain domain,
            @RequestParam(required = false) CoreInterpreterSource interpreterSource,
            @RequestParam(required = false) String q
    ) {
        return coreCommandService.getCommands(status, domain, interpreterSource, q);
    }

    @GetMapping("/commands/{commandId}")
    public CoreCommandDetailResponse getCommand(@PathVariable Long commandId) {
        return coreCommandService.getCommand(commandId);
    }

    @GetMapping("/commands/{commandId}/events")
    public CoreCommandEventsResponse getCommandEvents(@PathVariable Long commandId) {
        return coreCommandService.getCommandEvents(commandId);
    }

    @GetMapping("/commands/{commandId}/events/stream")
    public SseEmitter streamCommandEvents(@PathVariable Long commandId) {
        return coreStreamService.streamCommandEvents(commandId);
    }
}
