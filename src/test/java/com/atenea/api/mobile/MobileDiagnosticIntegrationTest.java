package com.atenea.api.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.atenea.AteneaApplication;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.JwtTokenService;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = AteneaApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "atenea.auth.bootstrap.enabled=false",
        "atenea.auth.jwt.secret=diagnostic-integration-synthetic-secret-only",
        "atenea.auth.sessions.enforcement-enabled=false",
        "atenea.attachments.enabled=true"
})
class MobileDiagnosticIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired OperatorRepository operators;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    private OperatorEntity owner;
    private OperatorEntity other;
    private String token;
    private String otherToken;

    @BeforeEach void setUp() {
        owner = createOperator();
        other = createOperator();
        token = access(owner);
        otherToken = access(other);
    }

    @AfterEach void cleanupOwnedFixturesOnly() {
        for (var operator : new OperatorEntity[] {owner, other}) {
            if (operator == null) continue;
            jdbc.update("DELETE FROM mobile_diagnostic_report WHERE operator_id = ?", operator.getId());
            operators.deleteById(operator.getId());
        }
    }

    @Test void authenticatedUploadSurvivesEnabledScopedAttachmentsAndIsDiscoverableAndDownloadable() throws Exception {
        JsonNode receipt = upload(report(), token);
        String id = receipt.path("id").asText();
        assertEquals(report().length, receipt.path("sizeBytes").asInt());
        assertTrue(receipt.path("sha256").asText().matches("[0-9a-f]{64}"));
        assertFalse(receipt.has("storedPath"));
        assertFalse(receipt.has("operatorId"));
        assertFalse(receipt.has("reportBytes"));
        mvc.perform(get("/api/mobile/diagnostics/latest").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/mobile/diagnostics").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/mobile/diagnostics/{id}", id).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get(receipt.path("contentPath").asText()).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(content().bytes(report()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(multipart("/api/mobile/uploads").file(file(report())).header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    @Test void identicalUploadIsIdempotentAndDoesNotChangeOriginalReceipt() throws Exception {
        JsonNode original = upload(report(), token);
        JsonNode retry = upload(report(), token);
        assertEquals(original, retry);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM mobile_diagnostic_report WHERE operator_id = ?",
                Integer.class, owner.getId()));
    }

    @Test void concurrentByteIdenticalUploadsPersistOneOriginalReceipt() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return upload(report(), token);
            });
            var second = threads.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return upload(report(), token);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally { start.countDown(); }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM mobile_diagnostic_report WHERE operator_id = ?",
                Integer.class, owner.getId()));
    }

    @Test void authenticationAndOwnershipProtectEveryReadAndUpload() throws Exception {
        JsonNode receipt = upload(report(), token);
        String id = receipt.path("id").asText();
        mvc.perform(multipart("/api/mobile/diagnostics").file(file(report()))).andExpect(status().isUnauthorized());
        for (String path : new String[] {"/api/mobile/diagnostics", "/api/mobile/diagnostics/latest",
                "/api/mobile/diagnostics/" + id, "/api/mobile/diagnostics/" + id + "/content"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/mobile/diagnostics/{id}", id).header("Authorization", otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(get(receipt.path("contentPath").asText()).header("Authorization", otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/mobile/diagnostics/latest").header("Authorization", otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/mobile/diagnostics").header("Authorization", otherToken))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test void latestTracksServerReceiptTimeAndNeverCreatesNewEvidence() throws Exception {
        upload(report(), token);
        byte[] updated = new String(report(), StandardCharsets.UTF_8).replace("manual_diagnostics_upload", "second_report")
                .getBytes(StandardCharsets.UTF_8);
        JsonNode second = upload(updated, token);
        for (int query = 0; query < 2; query++) {
            mvc.perform(get("/api/mobile/diagnostics/latest").header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(second.path("id").asText()));
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM mobile_diagnostic_report WHERE operator_id = ?",
                Integer.class, owner.getId()));
        mvc.perform(get("/api/mobile/diagnostics?limit=21").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    @Test void onlyBoundedDiagnosticJsonIsAcceptedAndChecksumMismatchFailsClosed() throws Exception {
        mvc.perform(multipart("/api/mobile/diagnostics")
                        .file(file("{\"command\":\"not a report\"}".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/mobile/diagnostics")
                        .file(new MockMultipartFile("file", "report.json", "text/plain", report()))
                        .header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/mobile/diagnostics").file(file(new byte[4 * 1024 * 1024 + 1]))
                        .header("Authorization", token)).andExpect(status().isPayloadTooLarge());
        JsonNode receipt = upload(report(), token);
        jdbc.update("UPDATE mobile_diagnostic_report SET sha256 = ? WHERE id = ? AND operator_id = ?",
                "0".repeat(64), UUID.fromString(receipt.path("id").asText()), owner.getId());
        mvc.perform(get(receipt.path("contentPath").asText()).header("Authorization", token))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.details[0]").value("DIAGNOSTIC_EVIDENCE_MISMATCH"));
    }

    private JsonNode upload(byte[] bytes, String authorization) throws Exception {
        return mapper.readTree(mvc.perform(multipart("/api/mobile/diagnostics").file(file(bytes))
                .header("Authorization", authorization)).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsByteArray());
    }
    private OperatorEntity createOperator() {
        var entity = new OperatorEntity();
        entity.setEmail("diagnostic-" + UUID.randomUUID() + "@synthetic.atenea.test");
        entity.setDisplayName("Owned diagnostic test fixture");
        entity.setPasswordHash("synthetic-unused");
        entity.setActive(true);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return operators.saveAndFlush(entity);
    }
    private String access(OperatorEntity entity) {
        return "Bearer " + tokens.issueAccessToken(new AuthenticatedOperator(
                entity.getId(), entity.getEmail(), entity.getDisplayName())).token();
    }
    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "ignored.json", "application/json", bytes);
    }
    private static byte[] report() {
        return """
                {"generatedAt":"2026-10-07T10:00:00Z","reason":"manual_diagnostics_upload",
                 "app":{"versionName":"0.5.109","versionCode":142},"device":{"model":"SM-M135F"},
                 "runtime":{},"lastCrash":{"exception":"java.lang.IllegalStateException",
                 "message":"Synthetic scroll failure","stacktrace":"synthetic.Conversation.scroll"},
                 "processExits":[],"events":[]}
                """.getBytes(StandardCharsets.UTF_8);
    }
}
