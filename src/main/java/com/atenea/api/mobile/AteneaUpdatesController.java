package com.atenea.api.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.service.mobile.AteneaUpdatesService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AteneaUpdatesController {
    private final AteneaUpdatesService service;
    private final com.atenea.service.mobile.ProductUpdateAnnouncementService announcements;
    public AteneaUpdatesController(AteneaUpdatesService service,com.atenea.service.mobile.ProductUpdateAnnouncementService announcements) { this.service=service;this.announcements=announcements; }
    @GetMapping("/api/mobile/atenea/updates")
    public AteneaUpdatesService.Updates read(@AuthenticationPrincipal AuthenticatedOperator actor,@RequestParam(defaultValue="1") int page) {
        return service.read(actor,page);
    }
    @PostMapping("/api/mobile/atenea/updates/announce")
    public com.atenea.service.mobile.ProductUpdateAnnouncementService.Announcement announce(@AuthenticationPrincipal AuthenticatedOperator actor,
            @RequestBody com.fasterxml.jackson.databind.JsonNode request) {
        if (!request.isObject() || !request.isEmpty()) throw new com.atenea.delivery.DeliveryRejectedException("CLOSED_REQUEST_REQUIRED");
        return announcements.announce(actor);
    }
}
