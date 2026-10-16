package com.atenea.api.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.service.mobile.ValidationRecoveryService;
import com.atenea.service.mobile.ValidationRecoveryStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/mobile/sessions/{sessionId}/validation-recovery")
public class ValidationRecoveryController {
    private final ValidationRecoveryService service;
    public ValidationRecoveryController(ValidationRecoveryService service) { this.service=service; }
    @GetMapping
    public ValidationRecoveryService.Overview observe(@PathVariable Long sessionId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal AuthenticatedOperator actor) {
        return service.observe(sessionId,authenticated(actor));
    }
    @PostMapping("/prepare")
    public ValidationRecoveryStore.View prepare(@PathVariable Long sessionId,@RequestBody(required=false) JsonNode body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal AuthenticatedOperator actor) {
        empty(body);return service.prepare(sessionId,authenticated(actor));
    }
    @PostMapping("/{operationId}/apply")
    public ValidationRecoveryStore.View apply(@PathVariable Long sessionId,@PathVariable UUID operationId,
            @RequestBody(required=false) JsonNode body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal AuthenticatedOperator actor) {
        empty(body);return service.apply(sessionId,operationId,authenticated(actor));
    }
    @PostMapping("/{operationId}/recover")
    public ValidationRecoveryStore.View recover(@PathVariable Long sessionId,@PathVariable UUID operationId,
            @RequestBody(required=false) JsonNode body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal AuthenticatedOperator actor) {
        empty(body);return service.recover(sessionId,operationId,authenticated(actor));
    }
    @PostMapping("/{operationId}/cancel")
    public ValidationRecoveryStore.View cancel(@PathVariable Long sessionId,@PathVariable UUID operationId,
            @RequestBody(required=false) JsonNode body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal AuthenticatedOperator actor) {
        empty(body);return service.cancel(sessionId,operationId,authenticated(actor));
    }
    @PostMapping("/investigate")
    public ValidationRecoveryStore.View investigate(@PathVariable Long sessionId,@RequestBody(required=false) JsonNode body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal AuthenticatedOperator actor) {
        empty(body);return service.investigate(sessionId,authenticated(actor));
    }
    private static void empty(JsonNode body) {
        if (body!=null && (!body.isObject() || !body.isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La acción no acepta comandos, rutas ni otros parámetros.");
    }
    private static AuthenticatedOperator authenticated(AuthenticatedOperator actor) {
        if (actor==null || actor.operatorId()==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return actor;
    }
}
