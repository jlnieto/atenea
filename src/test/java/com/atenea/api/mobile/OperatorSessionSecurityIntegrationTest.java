package com.atenea.api.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.AteneaApplication;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.JwtTokenService;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = AteneaApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "atenea.auth.bootstrap.enabled=true",
        "atenea.auth.bootstrap.email=operator@atenea.local",
        "atenea.auth.bootstrap.password=secret-pass",
        "atenea.auth.bootstrap.display-name=Integration Operator",
        "atenea.auth.jwt.secret=session-hardening-integration-secret-2026",
        "atenea.auth.sessions.enforcement-enabled=false",
        "atenea.auth.sessions.supported-protocol-version=FAMILY_V1"
})
class OperatorSessionSecurityIntegrationTest {

    private static final String PROTOCOL_VERSION = "FAMILY_V1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private OperatorRepository operatorRepository;

    @BeforeEach
    void clearSessions() {
        clearSessionRows();
    }

    @AfterEach
    void cleanSessions() {
        clearSessionRows();
    }

    @Test
    void legacyLoginAndRefreshStayLegacyWithoutAccidentalAdoption() throws Exception {
        JsonNode login = loginLegacy();
        assertEquals(0, familyCount());
        assertEquals(1, legacyTokenCount());
        assertFalse(accessPayload(login).has("sid"));

        JsonNode refreshed = refreshLegacy(login.path("refreshToken").asText(), 200);
        assertEquals(0, familyCount());
        assertEquals(2, legacyTokenCount());
        assertFalse(accessPayload(refreshed).has("sid"));
        authenticatedMe(refreshed.path("accessToken").asText(), 200);
    }

    @Test
    void negotiatedLoginCreatesFamilyAndIssuesTheFiveCanonicalClaims() throws Exception {
        JsonNode login = loginFamily("ANDROID", "Operator phone");
        JsonNode payload = accessPayload(login);

        assertEquals(1, familyCount());
        assertTrue(payload.path("sid").isTextual());
        assertTrue(payload.path("credentialVersion").isIntegralNumber());
        assertTrue(payload.path("roleVersion").isIntegralNumber());
        assertTrue(payload.path("auth_time").isIntegralNumber());
        assertEquals(List.of("pwd"), objectMapper.convertValue(
                payload.path("amr"), objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, String.class)));
        assertFalse(payload.has("sessionFamilyId"));
        assertTrue(payload.path("auth_time").asLong() <= payload.path("iat").asLong());

        JsonNode inventory = sessions(login.path("accessToken").asText());
        JsonNode item = inventory.get(0);
        assertEquals("ACTIVE", item.path("state").asText());
        assertTrue(item.path("current").asBoolean());
        for (String date : List.of("createdAt", "lastUsedAt", "absoluteExpiresAt")) {
            Instant rounded = Instant.parse(item.path(date).asText());
            assertEquals(0, rounded.getEpochSecond() % 60);
            assertEquals(0, rounded.getNano());
        }
        String serialized = inventory.toString().toLowerCase();
        assertFalse(serialized.contains("token"));
        assertFalse(serialized.contains("hash"));
        assertFalse(serialized.contains("useragent"));
    }

    @Test
    void invalidNegotiationStaysLegacyAndNeverCreatesOrAdoptsAFamily()
            throws Exception {
        JsonNode absent = json(loginRequest(objectMapper.createObjectNode()
                .put("email", "operator@atenea.local")
                .put("password", "secret-pass")
                .put("clientType", "ANDROID")
                .put("deviceLabel", "Unnegotiated phone").toString(), 200));
        assertFalse(accessPayload(absent).has("sid"));
        ObjectNode missingSingleFlight = familyLoginBody("ANDROID", "Incomplete phone");
        missingSingleFlight.remove("singleFlightRefresh");
        assertFalse(accessPayload(json(loginRequest(missingSingleFlight.toString(), 200))).has("sid"));
        assertFalse(accessPayload(json(loginRequest(familyLoginBody("ANDROID", "Contradictory phone")
                .put("singleFlightRefresh", false).toString(), 200))).has("sid"));
        assertFalse(accessPayload(json(loginRequest(familyLoginBody("ANDROID", "Unsupported phone")
                .put("sessionProtocolVersion", "FAMILY_V2").toString(), 200))).has("sid"));
        loginRequest("""
                {
                  "email":"operator@atenea.local",
                  "password":"secret-pass",
                  "clientType":"ANDROID",
                  "deviceLabel":"Duplicate phone",
                  "sessionProtocolVersion":"FAMILY_V1",
                  "sessionProtocolVersion":"FAMILY_V1",
                  "singleFlightRefresh":true
                }
                """, 400);
        assertEquals(0, familyCount());

        String legacyRefresh = absent.path("refreshToken").asText();
        JsonNode stillLegacy = json(refreshRequest(objectMapper.createObjectNode()
                .put("refreshToken", legacyRefresh)
                .put("sessionProtocolVersion", "FAMILY_V2")
                .put("singleFlightRefresh", true)
                .toString(), 200));
        assertFalse(accessPayload(stillLegacy).has("sid"));
        assertEquals(0, familyCount());

        JsonNode negotiated = loginFamily("ANDROID", "Negotiated phone");
        String refresh = negotiated.path("refreshToken").asText();
        refreshLegacy(refresh, 401);
        refreshRequest("""
                {
                  "refreshToken":"%s",
                  "sessionProtocolVersion":"FAMILY_V1",
                  "sessionProtocolVersion":"FAMILY_V1",
                  "singleFlightRefresh":true
                }
                """.formatted(refresh), 400);
        refreshFamily(refresh, 200);
    }

    @Test
    void explicitlyNegotiatedLegacyRefreshBecomesGenerationZeroWithSuccessorOne()
            throws Exception {
        String legacyRaw = insertLegacyRefresh("legacy-adoption");
        insertLegacyRefresh("unrelated-legacy");

        JsonNode adopted = refreshForAdoption(
                legacyRaw, "ANDROID", "Adopted phone", 200);
        JsonNode payload = accessPayload(adopted);
        UUID familyId = UUID.fromString(payload.path("sid").asText());

        assertEquals(1L, jdbcTemplate.queryForObject("""
                SELECT current_generation FROM operator_session_family WHERE id = ?
                """, Long.class, familyId));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM operator_session_family
                WHERE id = ? AND authenticated_at = created_at
                  AND authentication_method = 'pwd'
                """, Integer.class, familyId));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM operator_refresh_token
                WHERE session_family_id = ? AND generation = 0
                  AND consumed_at IS NOT NULL AND replaced_by_token_id IS NOT NULL
                """, Integer.class, familyId));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM operator_refresh_token
                WHERE session_family_id = ? AND generation = 1 AND revoked_at IS NULL
                """, Integer.class, familyId));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM operator_refresh_token
                WHERE session_family_id IS NULL AND revoked_at IS NULL
                """, Integer.class));
    }

    @Test
    void replayOfConsumedRefreshRevokesOnlyItsFamily() throws Exception {
        JsonNode compromised = loginFamily("ANDROID", "Replay phone");
        JsonNode unrelated = loginFamily("WEB", "Safe browser");
        String originalRefresh = compromised.path("refreshToken").asText();
        JsonNode rotated = refreshFamily(originalRefresh, 200);

        refreshFamily(originalRefresh, 401);
        authenticatedMe(rotated.path("accessToken").asText(), 401);
        authenticatedMe(unrelated.path("accessToken").asText(), 200);
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM operator_session_family
                WHERE revocation_reason = 'REPLAY_DETECTED'
                """, Integer.class));
    }

    @Test
    void concurrentFamilyRefreshAllowsOneRotationThenRevokesOnReplay() throws Exception {
        JsonNode login = loginFamily("ANDROID", "Concurrent phone");
        String originalRefresh = login.path("refreshToken").asText();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return refreshFamilyRequest(originalRefresh).getResponse().getStatus();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = futures.stream()
                    .map(future -> getWithin(future, 15))
                    .sorted()
                    .toList();
            assertEquals(List.of(200, 401), statuses);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals("REPLAY_DETECTED", jdbcTemplate.queryForObject(
                "SELECT revocation_reason FROM operator_session_family",
                String.class));
    }

    @Test
    void accountFamilyCredentialAndRoleChangesInvalidateAccessTokens() throws Exception {
        JsonNode selected = loginFamily("WEB", "Selected browser");
        mockMvc.perform(delete("/api/mobile/auth/sessions/{familyId}",
                        UUID.fromString(accessPayload(selected).path("sid").asText()))
                        .header("Authorization", "Bearer " + selected.path("accessToken").asText()))
                .andExpect(status().isNoContent());
        authenticatedMe(selected.path("accessToken").asText(), 401);

        JsonNode credential = loginFamily("WEB", "Credential browser");
        jdbcTemplate.update("""
                UPDATE operator_account SET credential_version = credential_version + 1
                WHERE id = ?
                """, operatorId());
        authenticatedMe(credential.path("accessToken").asText(), 401);

        JsonNode role = loginFamily("WEB", "Role browser");
        jdbcTemplate.update("""
                UPDATE operator_account SET role_version = role_version + 1 WHERE id = ?
                """, operatorId());
        authenticatedMe(role.path("accessToken").asText(), 401);

        JsonNode inactive = loginFamily("WEB", "Inactive browser");
        jdbcTemplate.update("UPDATE operator_account SET active = false WHERE id = ?", operatorId());
        authenticatedMe(inactive.path("accessToken").asText(), 401);
    }

    @Test
    void selectedRevocationRequiresOwnershipAndLeavesUnrelatedFamilyActive()
            throws Exception {
        JsonNode browser = loginFamily("WEB", "Work browser");
        JsonNode phone = loginFamily("ANDROID", "Operator phone");
        UUID phoneFamily = UUID.fromString(accessPayload(phone).path("sid").asText());

        mockMvc.perform(delete("/api/mobile/auth/sessions/{familyId}", phoneFamily)
                        .header("Authorization", "Bearer " + browser.path("accessToken").asText()))
                .andExpect(status().isNoContent());
        authenticatedMe(browser.path("accessToken").asText(), 200);
        authenticatedMe(phone.path("accessToken").asText(), 401);

        UUID foreignFamily = insertForeignFamily();
        mockMvc.perform(delete("/api/mobile/auth/sessions/{familyId}", foreignFamily)
                        .header("Authorization", "Bearer " + browser.path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM operator_session_family
                WHERE id = ? AND revoked_at IS NOT NULL
                """, Integer.class, foreignFamily));
    }

    @Test
    void currentOtherAndGlobalRevocationHaveDistinctEffects() throws Exception {
        JsonNode current = loginFamily("WEB", "Current browser");
        mockMvc.perform(delete("/api/mobile/auth/sessions/current")
                        .header("Authorization", "Bearer " + current.path("accessToken").asText()))
                .andExpect(status().isNoContent());
        authenticatedMe(current.path("accessToken").asText(), 401);

        JsonNode keeper = loginFamily("WEB", "Keeper browser");
        JsonNode other = loginFamily("ANDROID", "Other phone");
        JsonNode legacy = loginLegacy();
        mockMvc.perform(delete("/api/mobile/auth/sessions/others")
                        .header("Authorization", "Bearer " + keeper.path("accessToken").asText()))
                .andExpect(status().isNoContent());
        authenticatedMe(keeper.path("accessToken").asText(), 200);
        authenticatedMe(other.path("accessToken").asText(), 401);
        refreshLegacy(legacy.path("refreshToken").asText(), 401);

        long before = credentialVersion();
        JsonNode globalOther = loginFamily("ANDROID", "Global phone");
        mockMvc.perform(delete("/api/mobile/auth/sessions")
                        .header("Authorization", "Bearer " + keeper.path("accessToken").asText()))
                .andExpect(status().isNoContent());
        assertEquals(before + 1, credentialVersion());
        authenticatedMe(keeper.path("accessToken").asText(), 401);
        authenticatedMe(globalOther.path("accessToken").asText(), 401);
    }

    @Test
    void androidProtocolSupportsInventoryAndRemoteLogoutWithoutClientChanges()
            throws Exception {
        JsonNode session = loginFamily("ANDROID", "Protocol phone");
        assertEquals("ANDROID", sessions(session.path("accessToken").asText())
                .get(0).path("clientType").asText());

        mockMvc.perform(post("/api/mobile/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("refreshToken", session.path("refreshToken").asText())
                                .put("sessionProtocolVersion", PROTOCOL_VERSION)
                                .put("singleFlightRefresh", true)
                                .toString()))
                .andExpect(status().isNoContent());
        authenticatedMe(session.path("accessToken").asText(), 401);
    }

    @Test
    void rejectsUnsanitizedSessionMetadataWithoutCreatingAFamily() throws Exception {
        loginRequest(familyLoginBody("web", " unsafe label ").toString(), 401);
        assertEquals(0, familyCount());
    }

    @Test
    void enforcementOffKeepsLegacyAccessTokensCompatible() throws Exception {
        OperatorEntity operator = operatorRepository
                .findByEmailIgnoreCase("operator@atenea.local")
                .orElseThrow();
        String legacyAccessToken = jwtTokenService.issueAccessToken(
                new AuthenticatedOperator(
                        operator.getId(), operator.getEmail(), operator.getDisplayName()))
                .token();
        authenticatedMe(legacyAccessToken, 200);
    }

    private JsonNode loginLegacy() throws Exception {
        MvcResult result = loginRequest(objectMapper.createObjectNode()
                .put("email", "operator@atenea.local")
                .put("password", "secret-pass")
                .toString(), 200);
        return json(result);
    }

    private JsonNode loginFamily(String clientType, String deviceLabel) throws Exception {
        return json(loginRequest(familyLoginBody(clientType, deviceLabel).toString(), 200));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode familyLoginBody(
            String clientType,
            String deviceLabel
    ) {
        return objectMapper.createObjectNode()
                .put("email", "operator@atenea.local")
                .put("password", "secret-pass")
                .put("clientType", clientType)
                .put("deviceLabel", deviceLabel)
                .put("sessionProtocolVersion", PROTOCOL_VERSION)
                .put("singleFlightRefresh", true);
    }

    private MvcResult loginRequest(String body, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/mobile/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private JsonNode refreshLegacy(String refreshToken, int expectedStatus) throws Exception {
        return json(refreshRequest(objectMapper.createObjectNode()
                .put("refreshToken", refreshToken)
                .toString(), expectedStatus));
    }

    private JsonNode refreshFamily(String refreshToken, int expectedStatus) throws Exception {
        MvcResult result = refreshFamilyRequest(refreshToken);
        assertEquals(expectedStatus, result.getResponse().getStatus());
        return json(result);
    }

    private MvcResult refreshFamilyRequest(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/mobile/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("refreshToken", refreshToken)
                                .put("sessionProtocolVersion", PROTOCOL_VERSION)
                                .put("singleFlightRefresh", true)
                                .toString()))
                .andReturn();
    }

    private JsonNode refreshForAdoption(
            String refreshToken,
            String clientType,
            String deviceLabel,
            int expectedStatus
    ) throws Exception {
        return json(refreshRequest(objectMapper.createObjectNode()
                .put("refreshToken", refreshToken)
                .put("clientType", clientType)
                .put("deviceLabel", deviceLabel)
                .put("sessionProtocolVersion", PROTOCOL_VERSION)
                .put("singleFlightRefresh", true)
                .toString(), expectedStatus));
    }

    private MvcResult refreshRequest(String body, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/mobile/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private JsonNode sessions(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/mobile/auth/sessions")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].familyId").isString())
                .andReturn();
        return json(result);
    }

    private void authenticatedMe(String accessToken, int expectedStatus) throws Exception {
        assertEquals(expectedStatus, mockMvc.perform(get("/api/mobile/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn()
                .getResponse()
                .getStatus());
    }

    private JsonNode accessPayload(JsonNode session) throws Exception {
        String[] parts = session.path("accessToken").asText().split("\\.");
        return objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private JsonNode json(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
    }

    private int getWithin(Future<Integer> future, int seconds) {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent refresh did not finish", exception);
        }
    }

    private String insertLegacyRefresh(String seed) throws Exception {
        String raw = seed + "." + UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO operator_refresh_token (
                    operator_id, token_hash, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                operatorId(),
                sha256(raw),
                Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now),
                Timestamp.from(now));
        return raw;
    }

    private UUID insertForeignFamily() {
        Instant now = Instant.now();
        long foreignOperator = jdbcTemplate.queryForObject("""
                INSERT INTO operator_account (
                    email, display_name, password_hash, active,
                    codex_operations_role, credential_version, role_version,
                    created_at, updated_at)
                VALUES (?, 'Foreign synthetic operator', 'synthetic-hash', true,
                    'ROUTINE_OPERATOR', 0, 0, ?, ?)
                RETURNING id
                """, Long.class, "foreign-" + UUID.randomUUID() + "@atenea.test",
                Timestamp.from(now), Timestamp.from(now));
        UUID familyId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO operator_session_family (
                    id, operator_id, client_type, device_label,
                    current_generation, created_at, last_used_at,
                    absolute_expires_at)
                VALUES (?, ?, 'WEB', 'Foreign browser', 0, ?, ?, ?)
                """, familyId, foreignOperator,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)));
        return familyId;
    }

    private long operatorId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM operator_account WHERE email = 'operator@atenea.local'",
                Long.class);
    }

    private long credentialVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT credential_version FROM operator_account WHERE id = ?",
                Long.class,
                operatorId());
    }

    private int familyCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM operator_session_family", Integer.class);
    }

    private int legacyTokenCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM operator_refresh_token WHERE session_family_id IS NULL
                """, Integer.class);
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void clearSessionRows() {
        jdbcTemplate.update("DELETE FROM operator_refresh_token");
        jdbcTemplate.update("DELETE FROM operator_session_family");
        jdbcTemplate.update("DELETE FROM operator_account WHERE email LIKE 'foreign-%@atenea.test'");
        jdbcTemplate.update("""
                UPDATE operator_account
                SET credential_version = 0, role_version = 0, active = true
                WHERE email = 'operator@atenea.local'
                """);
    }
}
