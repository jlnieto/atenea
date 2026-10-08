package com.atenea.delivery;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.AuthenticatedSession;
import com.atenea.auth.action.PrivilegedActionAuthorizationGrant;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MobileDeliveryController {
    private final MobileDeliveryService service;
    private final SourceUpdateService sourceUpdates;
    public MobileDeliveryController(MobileDeliveryService service, SourceUpdateService sourceUpdates) {
        this.service = service; this.sourceUpdates = sourceUpdates;
    }

    @GetMapping("/api/mobile/sessions/{sessionId}/delivery")
    public DeliveryState list(@PathVariable Long sessionId,
            @AuthenticationPrincipal AuthenticatedOperator actor) {
        return new DeliveryState(service.isEnabled(),service.list(sessionId, actor),
                service.observeIntegration(sessionId, actor), sourceUpdates.isEnabled(), sourceUpdates.observe(sessionId, actor));
    }
    public record DeliveryState(boolean enabled, List<DeliveryOperation.DeliveryView> operations,
            MobileDeliveryService.IntegrationObservation integration, boolean sourceUpdateEnabled,
            SourceUpdateOperation.View sourceUpdate) { }

    @PostMapping("/api/mobile/sessions/{sessionId}/delivery/resolve-conflicts")
    public SourceUpdateOperation.View resolveConflicts(@PathVariable Long sessionId,
            @AuthenticationPrincipal AuthenticatedOperator actor, @RequestBody JsonNode request) {
        exact(request, Set.of());
        return sourceUpdates.request(sessionId, actor);
    }

    @PostMapping("/api/mobile/sessions/{sessionId}/delivery/pr")
    public DeliveryOperation.DeliveryView publish(@PathVariable Long sessionId,
            @AuthenticationPrincipal AuthenticatedOperator actor, @RequestBody JsonNode request) {
        exact(request, Set.of());
        return service.request(sessionId, actor, "PUBLISH_PR", DeliveryTarget.APP_PROD);
    }
    @PostMapping("/api/mobile/sessions/{sessionId}/delivery/integrate")
    public DeliveryOperation.DeliveryView integrate(@PathVariable Long sessionId,
            @AuthenticationPrincipal AuthenticatedOperator actor, @RequestBody JsonNode request) {
        exact(request, Set.of());
        return service.request(sessionId, actor, "INTEGRATE", DeliveryTarget.APP_PROD);
    }
    @PostMapping("/api/mobile/sessions/{sessionId}/delivery/release-plan")
    public DeliveryOperation.DeliveryView plan(@PathVariable Long sessionId,
            @AuthenticationPrincipal AuthenticatedOperator actor, @RequestBody JsonNode request) {
        exact(request, Set.of("target"));
        DeliveryTarget target;
        try { target = DeliveryTarget.valueOf(request.path("target").textValue()); }
        catch (RuntimeException exception) { throw new DeliveryRejectedException("CLOSED_REQUEST_REQUIRED"); }
        return service.request(sessionId, actor, "RELEASE", target);
    }
    @PostMapping("/api/mobile/delivery/{id}/authorize")
    public PrivilegedActionAuthorizationGrant authorize(@PathVariable UUID id, Authentication authentication,
            @RequestBody JsonNode request) {
        exact(request, Set.of("totp"));
        String code = request.path("totp").textValue();
        if (code == null || !code.matches("[0-9]{6}")) throw new DeliveryRejectedException("TOTP_REQUIRED");
        return service.authorize(id, session(authentication), code);
    }
    @PostMapping("/api/mobile/delivery/{id}/confirm")
    public DeliveryOperation.DeliveryView confirm(@PathVariable UUID id, Authentication authentication,
            @RequestBody JsonNode request) {
        exact(request, Set.of("authorization"));
        UUID authorization;
        try { authorization = UUID.fromString(request.path("authorization").textValue()); }
        catch (RuntimeException exception) { throw new DeliveryRejectedException("AUTHORIZATION_REQUIRED"); }
        return service.confirm(id, session(authentication), authorization);
    }

    static void exact(JsonNode request, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        request.fieldNames().forEachRemaining(actual::add);
        if (!request.isObject() || !actual.equals(expected)) throw new DeliveryRejectedException("CLOSED_REQUEST_REQUIRED");
    }
    private AuthenticatedSession session(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedOperator actor)
                || !(authentication.getDetails() instanceof UUID family)) {
            throw new DeliveryRejectedException("AUTHENTICATED_SESSION_REQUIRED");
        }
        return new AuthenticatedSession(actor, family, Instant.now(), List.of("access_token"));
    }
}
