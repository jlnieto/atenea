package com.atenea.auth.webauthn;

import com.atenea.auth.OperatorAuthProperties;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Public credential association for the fixed stable app, derived only from trusted configuration. */
@RestController
public class AndroidCredentialAssociationController {
    private final OperatorAuthProperties properties;

    public AndroidCredentialAssociationController(OperatorAuthProperties properties) {
        this.properties = properties;
    }

    @GetMapping(value = "/.well-known/assetlinks.json", produces = "application/json")
    public List<Map<String, Object>> associations() {
        var config = properties.getWebAuthn();
        if (!config.isEnabled() || config.getAndroidOrigins() == null || config.getAndroidOrigins().isEmpty()) {
            return List.of();
        }
        if (!"atenea.yudri.es".equals(config.getRelyingPartyId())) throw invalidConfiguration();
        List<String> certificates = config.getAndroidOrigins().stream().map(this::certificate).distinct().toList();
        return List.of(Map.of("relation", List.of("delegate_permission/common.get_login_creds"),
                "target", Map.of("namespace", "android_app", "package_name", "com.atenea.android",
                        "sha256_cert_fingerprints", certificates)));
    }

    private String certificate(String origin) {
        if (origin == null || !origin.matches("android:apk-key-hash:[A-Za-z0-9_-]{43}")) throw invalidConfiguration();
        try {
            String encoded = origin.substring("android:apk-key-hash:".length());
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                throw invalidConfiguration();
            }
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(bytes);
        } catch (IllegalArgumentException rejected) {
            throw invalidConfiguration();
        }
    }

    private ResponseStatusException invalidConfiguration() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Android credential association unavailable");
    }
}
