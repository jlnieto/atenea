package com.atenea.delivery;

import com.atenea.auth.AuthenticatedOperator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mobile/sessions/{sessionId}/retirement")
public class WorkSessionRetirementController {
    private final WorkSessionRetirementService service;
    public WorkSessionRetirementController(WorkSessionRetirementService service) { this.service=service; }
    @GetMapping
    public WorkSessionRetirementService.View observe(@PathVariable Long sessionId,
            @AuthenticationPrincipal AuthenticatedOperator actor) { return service.observe(sessionId,actor); }
    @PostMapping
    public WorkSessionRetirementService.View request(@PathVariable Long sessionId,
            @AuthenticationPrincipal AuthenticatedOperator actor,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode request) {
        exact(request); return service.request(sessionId,actor,key,false);
    }
    @PostMapping("/reconcile")
    public WorkSessionRetirementService.View reconcile(@PathVariable Long sessionId,
            @AuthenticationPrincipal AuthenticatedOperator actor,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode request) {
        exact(request); return service.request(sessionId,actor,key,true);
    }
    private void exact(JsonNode request) {
        if (request==null || !request.isObject() || request.size()!=0)
            throw new DeliveryRejectedException("RETIREMENT_EXACT_REQUEST_REQUIRED");
    }
}
