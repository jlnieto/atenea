package com.atenea.delivery;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.AuthenticatedSession;
import com.atenea.auth.action.PrivilegedActionAuthorizationGrant;
import com.atenea.auth.webauthn.WebAuthnAuthenticationRequest;
import com.atenea.auth.webauthn.WebAuthnOptionsResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MobileReleasePasskeyController {
    private final MobileReleasePasskeyService service;
    private final ReleasePasskeySetupService setup;

    public MobileReleasePasskeyController(MobileReleasePasskeyService service, ReleasePasskeySetupService setup) {
        this.service = service;
        this.setup = setup;
    }

    @PostMapping("/api/mobile/delivery/{id}/authorize/passkey/options")
    public WebAuthnOptionsResponse options(@PathVariable UUID id, Authentication authentication,
            @RequestHeader("X-Atenea-Android-Origin") String origin, @RequestBody JsonNode request) {
        MobileDeliveryController.exact(request, Set.of());
        return service.options(id, session(authentication), origin);
    }

    @PostMapping("/api/mobile/delivery/{id}/authorize/passkey")
    public PrivilegedActionAuthorizationGrant authorize(@PathVariable UUID id, Authentication authentication,
            @RequestHeader("X-Atenea-Android-Origin") String origin, @RequestBody JsonNode request) {
        return service.authorize(id, session(authentication), origin, assertion(request));
    }

    @PostMapping("/api/mobile/release-passkeys/setup/options")
    public WebAuthnOptionsResponse setupOptions(Authentication authentication,
            @RequestHeader("X-Atenea-Android-Origin") String origin, @RequestBody JsonNode request) {
        MobileDeliveryController.exact(request, Set.of());
        return setup.options(session(authentication), origin);
    }

    @GetMapping("/api/mobile/release-passkeys")
    public ReleasePasskeySetupService.Status status(Authentication authentication,
            @RequestHeader("X-Atenea-Android-Origin") String origin) {
        return setup.status(session(authentication),origin);
    }

    @PostMapping("/api/mobile/release-passkeys/setup")
    public java.util.Map<String,Boolean> setup(Authentication authentication,
            @RequestHeader("X-Atenea-Android-Origin") String origin, @RequestBody JsonNode request) {
        MobileDeliveryController.exact(request, Set.of("totp", "requestId", "credentialId", "userHandle",
                "clientDataJson", "authenticatorData", "signature"));
        String totp = request.path("totp").textValue();
        if (totp==null || !totp.matches("[0-9]{6}")) throw new DeliveryRejectedException("TOTP_REQUIRED");
        var proof = ((com.fasterxml.jackson.databind.node.ObjectNode)request).deepCopy();
        proof.remove("totp");
        setup.approve(session(authentication), origin, totp, assertion(proof));
        return java.util.Map.of("configured",true);
    }

    private WebAuthnAuthenticationRequest assertion(JsonNode request) {
        MobileDeliveryController.exact(request, Set.of("requestId", "credentialId", "userHandle",
                "clientDataJson", "authenticatorData", "signature"));
        UUID requestId;
        try {
            requestId = UUID.fromString(request.path("requestId").textValue());
            if (!requestId.toString().equals(request.path("requestId").textValue())) throw new IllegalArgumentException();
        } catch (RuntimeException rejected) {
            throw new DeliveryRejectedException("CLOSED_REQUEST_REQUIRED");
        }
        for (String key : List.of("credentialId", "clientDataJson", "authenticatorData", "signature")) {
            if (!request.path(key).isTextual() || request.path(key).asText().isBlank()) {
                throw new DeliveryRejectedException("CLOSED_REQUEST_REQUIRED");
            }
        }
        if (!request.path("userHandle").isNull() && !request.path("userHandle").isTextual()) {
            throw new DeliveryRejectedException("CLOSED_REQUEST_REQUIRED");
        }
        return new WebAuthnAuthenticationRequest(requestId, request.path("credentialId").textValue(),
                request.path("userHandle").textValue(), request.path("clientDataJson").textValue(),
                request.path("authenticatorData").textValue(), request.path("signature").textValue(),
                null, null, null, null);
    }

    private AuthenticatedSession session(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedOperator actor)
                || !(authentication.getDetails() instanceof UUID family)) {
            throw new DeliveryRejectedException("AUTHENTICATED_SESSION_REQUIRED");
        }
        return new AuthenticatedSession(actor, family, Instant.now(), List.of("access_token"));
    }
}
