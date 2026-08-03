package com.atenea.api.worksession;

import com.atenea.persistence.worksession.WorkSessionPreviewEntity;
import com.atenea.previews.PreviewActivationCommand;
import com.atenea.previews.PreviewProperties;
import com.atenea.previews.PreviewTunnel;
import com.atenea.previews.WorkSessionPreviewService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/sessions/{sessionId}/preview", "/api/mobile/sessions/{sessionId}/preview"})
public class WorkSessionPreviewController {

    private final WorkSessionPreviewService previewService;
    private final PreviewProperties previewProperties;

    public WorkSessionPreviewController(
            WorkSessionPreviewService previewService,
            PreviewProperties previewProperties
    ) {
        this.previewService = previewService;
        this.previewProperties = previewProperties;
    }

    @PostMapping("/activate")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkSessionPreviewResponse activate(
            @PathVariable Long sessionId,
            @RequestBody PreviewActivationRequest request
    ) {
        return response(previewService.activate(
                sessionId,
                new PreviewActivationCommand(
                        request.previewId(),
                        request.agentRunId(),
                        request.runtimeSessionId(),
                        request.allocationFingerprint())));
    }

    @GetMapping
    public WorkSessionPreviewResponse status(@PathVariable Long sessionId) {
        return response(previewService.status(sessionId));
    }

    @GetMapping("/history")
    public List<WorkSessionPreviewResponse> retained(@PathVariable Long sessionId) {
        return previewService.retained(sessionId).stream()
                .map(this::response)
                .toList();
    }

    @PostMapping("/{previewId}/renew")
    public WorkSessionPreviewResponse renew(
            @PathVariable Long sessionId,
            @PathVariable UUID previewId
    ) {
        return response(previewService.renew(sessionId, previewId));
    }

    @PostMapping("/{previewId}/stop")
    public WorkSessionPreviewResponse stop(
            @PathVariable Long sessionId,
            @PathVariable UUID previewId
    ) {
        return response(previewService.stop(sessionId, previewId));
    }

    @GetMapping("/{previewId}/localhost")
    public PreviewTunnel localhost(
            @PathVariable Long sessionId,
            @PathVariable UUID previewId,
            @RequestParam int localPort
    ) {
        return previewService.localhost(sessionId, previewId, localPort);
    }

    private WorkSessionPreviewResponse response(WorkSessionPreviewEntity preview) {
        return WorkSessionPreviewResponse.from(preview, previewProperties.isEnabled());
    }
}
