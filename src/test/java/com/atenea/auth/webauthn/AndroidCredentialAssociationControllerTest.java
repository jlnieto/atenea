package com.atenea.auth.webauthn;

import com.atenea.auth.OperatorAuthProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AndroidCredentialAssociationControllerTest {
    private final OperatorAuthProperties properties = new OperatorAuthProperties();

    @Test void disabledOrUnconfiguredDoesNotClaimAnAndroidAssociation() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AndroidCredentialAssociationController(properties)).build();
        mvc.perform(get("/.well-known/assetlinks.json")).andExpect(status().isOk()).andExpect(content().json("[]"));
        properties.getWebAuthn().setEnabled(true);
        mvc.perform(get("/.well-known/assetlinks.json")).andExpect(content().json("[]"));
    }

    @Test void associationUsesOnlyConfiguredCertificateAndFixedStablePackage() throws Exception {
        properties.getWebAuthn().setEnabled(true);
        properties.getWebAuthn().setAndroidOrigins(List.of("android:apk-key-hash:" + "A".repeat(43)));
        var mvc = MockMvcBuilders.standaloneSetup(new AndroidCredentialAssociationController(properties)).build();
        mvc.perform(get("/.well-known/assetlinks.json")).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.get_login_creds"))
            .andExpect(jsonPath("$[0].target.package_name").value("com.atenea.android"))
            .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[0]").value("00:".repeat(31) + "00"));
    }

    @Test void malformedOrNonCanonicalConfigurationNeverPublishesAPartialAssociation() throws Exception {
        properties.getWebAuthn().setEnabled(true);
        var mvc = MockMvcBuilders.standaloneSetup(new AndroidCredentialAssociationController(properties)).build();
        for (String invalid : List.of("https://foreign.test", "android:apk-key-hash:" + "x".repeat(43),
                "android:apk-key-hash:arbitrary", "*")) {
            properties.getWebAuthn().setAndroidOrigins(List.of("android:apk-key-hash:" + "A".repeat(43), invalid));
            mvc.perform(get("/.well-known/assetlinks.json")).andExpect(status().isServiceUnavailable());
        }
        properties.getWebAuthn().setAndroidOrigins(List.of("android:apk-key-hash:" + "A".repeat(43)));
        properties.getWebAuthn().setRelyingPartyId("foreign.test");
        mvc.perform(get("/.well-known/assetlinks.json")).andExpect(status().isServiceUnavailable());
    }
}
