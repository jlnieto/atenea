package com.atenea.api.worksession;

import com.atenea.attachments.WorkSessionAttachmentService;
import com.atenea.attachments.AttachmentCapability;
import com.atenea.attachments.AttachmentCapabilityService;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping({"/api/sessions/{sessionId}/attachments", "/api/mobile/sessions/{sessionId}/attachments"})
public class WorkSessionAttachmentController {

    private final WorkSessionAttachmentService attachmentService;
    private final AttachmentCapabilityService capabilityService;

    public WorkSessionAttachmentController(
            WorkSessionAttachmentService attachmentService,
            AttachmentCapabilityService capabilityService
    ) {
        this.attachmentService = attachmentService;
        this.capabilityService = capabilityService;
    }

    @GetMapping("/capability")
    public AttachmentCapability capability(@PathVariable Long sessionId) {
        return capabilityService.get(sessionId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public WorkSessionAttachmentResponse upload(
            @PathVariable Long sessionId,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID idempotencyKey,
            @RequestParam(name = "agentRunId", required = false) Long agentRunId,
            @RequestParam(required = false) AttachmentSource source,
            @RequestParam(required = false) AttachmentKind kind,
            @RequestParam(required = false) AttachmentRetentionClass retentionClass,
            @RequestParam MultipartFile file
    ) {
        return WorkSessionAttachmentResponse.from(attachmentService.upload(
                sessionId,
                idempotencyKey,
                agentRunId,
                source,
                kind,
                retentionClass,
                file));
    }

    @GetMapping
    public List<WorkSessionAttachmentResponse> list(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return attachmentService.list(sessionId, limit).stream()
                .map(WorkSessionAttachmentResponse::from)
                .toList();
    }

    @GetMapping("/screenshots")
    public List<WorkSessionAttachmentResponse> screenshots(
            @PathVariable Long sessionId,
            @RequestParam(required = false) AttachmentSource source,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return attachmentService.screenshots(sessionId, source, offset, limit).stream()
                .map(WorkSessionAttachmentResponse::from)
                .toList();
    }

    @GetMapping("/{attachmentId}")
    public WorkSessionAttachmentResponse metadata(
            @PathVariable Long sessionId,
            @PathVariable UUID attachmentId
    ) {
        return WorkSessionAttachmentResponse.from(attachmentService.get(sessionId, attachmentId));
    }

    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<byte[]> content(
            @PathVariable Long sessionId,
            @PathVariable UUID attachmentId
    ) {
        WorkSessionAttachmentService.Download download = attachmentService.download(sessionId, attachmentId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(download.metadata().getContentType()));
        headers.setContentLength(download.content().length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(download.metadata().getOriginalFilename(), StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl("private, no-store");
        return new ResponseEntity<>(download.content(), headers, HttpStatus.OK);
    }
}
