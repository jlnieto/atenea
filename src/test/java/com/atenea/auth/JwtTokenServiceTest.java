package com.atenea.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.auth.session.SessionVersions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    private static final String SECRET = "jwt-session-claim-test-secret-2026";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JwtTokenService tokenService;
    private AuthenticatedOperator operator;

    @BeforeEach
    void setUp() {
        OperatorAuthProperties properties = new OperatorAuthProperties();
        properties.getJwt().setIssuer("jwt-test");
        properties.getJwt().setSecret(SECRET);
        tokenService = new JwtTokenService(objectMapper, properties);
        operator = new AuthenticatedOperator(7L, "operator@atenea.test", "Operator");
    }

    @Test
    void familyTokenUsesAndValidatesExactlyTheFiveCanonicalSessionClaims() throws Exception {
        UUID familyId = UUID.randomUUID();
        Instant authenticatedAt = Instant.now().minusSeconds(30);
        String token = tokenService.issueAccessToken(
                operator,
                familyId,
                new SessionVersions(4, 2),
                authenticatedAt,
                List.of("pwd"))
                .token();

        ObjectNode payload = payload(token);
        assertTrue(payload.has("sid"));
        assertTrue(payload.has("credentialVersion"));
        assertTrue(payload.has("roleVersion"));
        assertTrue(payload.has("auth_time"));
        assertTrue(payload.has("amr"));
        assertFalse(payload.has("sessionFamilyId"));

        JwtTokenService.ParsedAccessToken parsed = tokenService.parseSessionAccessToken(token);
        assertEquals(familyId, parsed.sessionFamilyId());
        assertEquals(4L, parsed.credentialVersion());
        assertEquals(2L, parsed.roleVersion());
        assertEquals(authenticatedAt.getEpochSecond(), parsed.authenticatedAt().getEpochSecond());
        assertEquals(List.of("pwd"), parsed.authenticationMethods());

        for (String claim : List.of(
                "sid", "credentialVersion", "roleVersion", "auth_time", "amr")) {
            ObjectNode incomplete = payload.deepCopy();
            incomplete.remove(claim);
            assertThrows(
                    OperatorAuthenticationException.class,
                    () -> tokenService.parseSessionAccessToken(sign(token, incomplete)),
                    claim);
        }
    }

    @Test
    void malformedCanonicalClaimsAndLegacyAliasFailClosed() throws Exception {
        String token = tokenService.issueAccessToken(
                operator,
                UUID.randomUUID(),
                new SessionVersions(1, 1),
                Instant.now().minusSeconds(10),
                List.of("pwd"))
                .token();
        ObjectNode payload = payload(token);

        assertRejected(token, payload.deepCopy().put("sid", "not-a-uuid"));
        assertRejected(token, payload.deepCopy().put("credentialVersion", -1));
        assertRejected(token, payload.deepCopy().put("roleVersion", "1"));
        assertRejected(token, payload.deepCopy().put(
                "auth_time", payload.path("iat").asLong() + 1));
        ObjectNode emptyAmr = payload.deepCopy();
        emptyAmr.putArray("amr");
        assertRejected(token, emptyAmr);
        ObjectNode duplicateAmr = payload.deepCopy();
        duplicateAmr.putArray("amr").add("pwd").add("pwd");
        assertRejected(token, duplicateAmr);
        assertRejected(token, payload.deepCopy().put("sessionFamilyId", UUID.randomUUID().toString()));
    }

    @Test
    void legacyTokenRemainsValidOnlyWithoutSessionClaims() throws Exception {
        String token = tokenService.issueAccessToken(operator).token();
        JwtTokenService.ParsedAccessToken parsed = tokenService.parseSessionAccessToken(token);

        assertFalse(parsed.sessionBound());
        assertNull(parsed.sessionFamilyId());
        assertNull(parsed.credentialVersion());
        assertNull(parsed.roleVersion());
        assertNull(parsed.authenticatedAt());
        assertTrue(parsed.authenticationMethods().isEmpty());
    }

    private void assertRejected(String token, ObjectNode payload) {
        assertThrows(
                OperatorAuthenticationException.class,
                () -> tokenService.parseSessionAccessToken(sign(token, payload)));
    }

    private ObjectNode payload(String token) throws Exception {
        String[] parts = token.split("\\.");
        return (ObjectNode) objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private String sign(String originalToken, JsonNode payload) throws Exception {
        String header = originalToken.split("\\.")[0];
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(payload));
        String content = header + "." + encodedPayload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        return content + "." + signature;
    }
}
