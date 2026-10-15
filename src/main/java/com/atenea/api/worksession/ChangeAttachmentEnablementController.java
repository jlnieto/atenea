package com.atenea.api.worksession;

import com.atenea.attachments.AttachmentCapability;
import com.atenea.attachments.ChangeAttachmentEnablementService;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.DeliveryRejectedException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ChangeAttachmentEnablementController {
    private final ChangeAttachmentEnablementService service;
    public ChangeAttachmentEnablementController(ChangeAttachmentEnablementService service) { this.service=service; }

    @PostMapping("/api/mobile/sessions/{id}/attachments/enable")
    public AttachmentCapability enable(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedOperator actor,
            @RequestBody JsonNode request) {
        if (!request.isObject() || !request.isEmpty()) throw new DeliveryRejectedException("CLOSED_REQUEST_REQUIRED");
        return service.enable(id, actor);
    }
}
