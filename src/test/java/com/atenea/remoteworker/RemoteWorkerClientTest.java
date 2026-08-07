package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkloadClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteWorkerClientTest {
    private static final String TEST_CANONICAL_COMMIT = "1".repeat(40);

    @TempDir
    Path temporary;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicReference<byte[]> workerErrorResponse = new AtomicReference<>();
    private final AtomicReference<String> workerErrorContentType =
            new AtomicReference<>("application/json");
    private final AtomicInteger workerErrorStatus = new AtomicInteger(409);
    private final AtomicReference<Consumer<Map<String, Object>>> releaseReceiptMutation =
            new AtomicReference<>();
    private final AtomicReference<String> releaseIdempotencyHeader = new AtomicReference<>();
    private HttpServer server;
    private RemoteWorkerProperties properties;
    private RemoteWorkerClient client;
    private ProjectCodexAttachmentManifestService attachmentManifestService;

    @BeforeEach
    void setUp() throws IOException {
        Path token = temporary.resolve("worker.token");
        Files.writeString(token, "t".repeat(64));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/executions", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            String path = exchange.getRequestURI().getPath();
            if (!"/v1/executions".equals(path)) {
                String dispatchId = path.split("/")[3];
                byte[] response;
                if (path.endsWith("/doctor")) {
                    response = objectMapper.writeValueAsBytes(doctorResponse(dispatchId, request));
                } else {
                    response = objectMapper.writeValueAsBytes(operationResponse(dispatchId, request));
                }
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.ofEntries(
                    java.util.Map.entry("dispatchId", request.get("dispatchId").asText()),
                    java.util.Map.entry("executionId", "4ee2d311-b9da-4307-89b6-dd3110ef2057"),
                    java.util.Map.entry("sessionId", request.get("sessionId").asText()),
                    java.util.Map.entry("workspaceIdentity", request.get("workspaceIdentity").asText()),
                    java.util.Map.entry("workloadClass", request.get("workloadClass").asText()),
                    java.util.Map.entry("leaseGeneration", request.get("leaseGeneration").asLong()),
                    java.util.Map.entry("status", "QUEUED"),
                    java.util.Map.entry("statusReason", "Awaiting worker admission"),
                    java.util.Map.entry("revision", 1),
                    java.util.Map.entry("progress", 0),
                    java.util.Map.entry("progressEvents", java.util.List.of(java.util.Map.of(
                            "dispatchId", request.get("dispatchId").asText(),
                            "executionId", "4ee2d311-b9da-4307-89b6-dd3110ef2057",
                            "sequence", 1,
                            "category", "ACCEPTED",
                            "occurredAt", Instant.parse("2026-07-29T06:00:00Z"),
                            "message", "Execution request accepted."))),
                    java.util.Map.entry("createdAt", Instant.parse("2026-07-29T06:00:00Z")),
                    java.util.Map.entry("updatedAt", Instant.parse("2026-07-29T06:00:00Z"))));
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/project-workspaces/ensure", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            byte[] forcedError = workerErrorResponse.get();
            if (forcedError != null) {
                exchange.getResponseHeaders().set(
                        "Content-Type", workerErrorContentType.get());
                exchange.sendResponseHeaders(workerErrorStatus.get(), forcedError.length);
                exchange.getResponseBody().write(forcedError);
                exchange.close();
                return;
            }
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.ofEntries(
                    java.util.Map.entry("state", "ready"),
                    java.util.Map.entry("sessionId", request.get("sessionId").asText()),
                    java.util.Map.entry("workspaceIdentity", request.get("workspaceIdentity").asText()),
                    java.util.Map.entry("projectId", request.get("projectId").asText()),
                    java.util.Map.entry("workspaceBranch", request.get("workspaceBranch").asText()),
                    java.util.Map.entry("slot", "slot4"),
                    java.util.Map.entry("canonicalCommit", request.get("commit").asText()),
                    java.util.Map.entry("selectionEnabled", true),
                    java.util.Map.entry("executionEnabled", true),
                    java.util.Map.entry("valuesExposed", false)));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/project-workspaces/release", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            releaseIdempotencyHeader.set(
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            Map<String, Object> receipt = workspaceReleaseReceipt(requestBody.get());
            Consumer<Map<String, Object>> mutation = releaseReceiptMutation.get();
            if (mutation != null) {
                mutation.accept(receipt);
            }
            byte[] response = objectMapper.writeValueAsBytes(receipt);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/project-workspaces/capacity-owner", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "schemaVersion", "project-workspace-capacity-owner-v1",
                    "state", "OWNED",
                    "sessionId", request.get("sessionId").asText(),
                    "workspaceIdentity", request.get("workspaceIdentity").asText(),
                    "projectId", ProjectCodexIdentity.PROJECT_IDENTITY,
                    "workerId", ProjectCodexIdentity.WORKER_ID,
                    "requestFingerprintSha256", canonicalSha256(request),
                    "ownershipFingerprintSha256", "7".repeat(64),
                    "valuesExposed", false));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/project-workspaces/release-preflight", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            releaseIdempotencyHeader.set(
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            JsonNode request = requestBody.get();
            byte[] response = objectMapper.writeValueAsBytes(Map.ofEntries(
                    Map.entry("schemaVersion", "project-workspace-release-diagnosis-v1"),
                    Map.entry("state", "PREFLIGHT_ACCEPTED"),
                    Map.entry("operationId", request.get("operationId").asText()),
                    Map.entry("sessionId", request.get("sessionId").asText()),
                    Map.entry("workspaceIdentity", request.get("workspaceIdentity").asText()),
                    Map.entry("projectId", ProjectCodexIdentity.PROJECT_IDENTITY),
                    Map.entry("workerId", ProjectCodexIdentity.WORKER_ID),
                    Map.entry("requestFingerprintSha256", canonicalSha256(request)),
                    Map.entry("ownershipFingerprintSha256", "8".repeat(64)),
                    Map.entry("allocationFingerprintSha256", "9".repeat(64)),
                    Map.entry("valuesExposed", false)));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/project-workspaces/draft-fingerprint", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.ofEntries(
                    java.util.Map.entry("state", "draft_blocked_ready"),
                    java.util.Map.entry("sessionId", request.get("sessionId").asText()),
                    java.util.Map.entry("workspaceIdentity", request.get("workspaceIdentity").asText()),
                    java.util.Map.entry("projectId", request.get("projectId").asText()),
                    java.util.Map.entry("retainedHead", "0".repeat(40)),
                    java.util.Map.entry("acceptedCommit", request.get("acceptedCommit").asText()),
                    java.util.Map.entry("fingerprintSha256", "3".repeat(64)),
                    java.util.Map.entry("stagedChangeCount", 2),
                    java.util.Map.entry("unstagedChangeCount", 3),
                    java.util.Map.entry("untrackedChangeCount", 4),
                    java.util.Map.entry("valuesExposed", false)));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/project-workspaces/source-tree-fingerprint", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.ofEntries(
                    java.util.Map.entry("state", "observed"),
                    java.util.Map.entry("sessionId", request.get("sessionId").asText()),
                    java.util.Map.entry("workspaceIdentity", request.get("workspaceIdentity").asText()),
                    java.util.Map.entry("projectId", request.get("projectId").asText()),
                    java.util.Map.entry("headCommit", request.get("commit").asText()),
                    java.util.Map.entry("fingerprintSha256", "4".repeat(64)),
                    java.util.Map.entry("stagedChangeCount", 1),
                    java.util.Map.entry("unstagedChangeCount", 2),
                    java.util.Map.entry("untrackedChangeCount", 3),
                    java.util.Map.entry("valuesExposed", false)));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/project-workspaces/validations", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.ofEntries(
                    java.util.Map.entry("validationId", request.get("validationId").asText()),
                    java.util.Map.entry("sessionId", request.get("sessionId").asText()),
                    java.util.Map.entry("workspaceIdentity", request.get("workspaceIdentity").asText()),
                    java.util.Map.entry("operation", request.get("operation").asText()),
                    java.util.Map.entry("definitionRevision", request.get("definitionRevision").asText()),
                    java.util.Map.entry(
                            "sourceTreeFingerprintSha256",
                            request.get("sourceTreeFingerprintSha256").asText()),
                    java.util.Map.entry("status", "SUCCEEDED"),
                    java.util.Map.entry("exitCode", 0),
                    java.util.Map.entry("durationMillis", 7),
                    java.util.Map.entry("artifactManifestSha256", "5".repeat(64)),
                    java.util.Map.entry("summary", "Closed validation passed"),
                    java.util.Map.entry("valuesExposed", false)));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/project-workspaces/repository-roles/ensure", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            String repository = ProjectCodexIdentity.REPOSITORY;
            String programBranch = "program/remote-codex-worker-platform";
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "sessionId", request.get("sessionId").asText(),
                    "workspaceIdentity", request.get("workspaceIdentity").asText(),
                    "changeIdentity", request.get("changeIdentity").asText(),
                    "roles", java.util.List.of(
                            role("ATENEA_CODE", ProjectCodexIdentity.BRANCH,
                                    request.get("codeCommit").asText(), "atenea-code-v1"),
                            role("PROGRAMME_OPENSPEC", programBranch,
                                    "3".repeat(40), "openspec-strict-v1"),
                            role("WORKER_SOURCE", programBranch,
                                    "3".repeat(40), "worker-contract-v1")),
                    "valuesExposed", false));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/codex/catalog", exchange -> {
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.of(
                    "schemaVersion", "codex-model-catalog-v1",
                    "catalogRevision", "125b9437e38f83e04cb10996fc70d3ab44c32082009b8e897cb08bb340b13187",
                    "workerId", "ax42-01",
                    "codexVersion", "0.145.0",
                    "generatedAt", Instant.parse("2026-07-31T23:00:00Z"),
                    "models", java.util.List.of(java.util.Map.of(
                            "modelId", "gpt-5.6-sol",
                            "displayName", "GPT-5.6 Sol",
                            "supportedEfforts", java.util.List.of(
                                    "none", "low", "medium", "high", "xhigh", "max"),
                            "defaultEffort", "medium",
                            "availability", "AVAILABLE"))));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/codex/update/stage", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.ofEntries(
                    java.util.Map.entry("schemaVersion", "codex-update-stage-v1"),
                    java.util.Map.entry("operation", request.get("operation").asText()),
                    java.util.Map.entry("workerId", "ax42-01"),
                    java.util.Map.entry("planId", request.get("planId").asText()),
                    java.util.Map.entry("candidateId", request.get("candidateId").asText()),
                    java.util.Map.entry("idempotencyKey", request.get("idempotencyKey").asText()),
                    java.util.Map.entry("state", "STAGED"),
                    java.util.Map.entry("codexVersion", "0.146.0"),
                    java.util.Map.entry("releaseDigestSha256", "1".repeat(64)),
                    java.util.Map.entry("catalogRevision", "2".repeat(64)),
                    java.util.Map.entry("releaseManifestSha256", "3".repeat(64)),
                    java.util.Map.entry("schemaManifestSha256", "4".repeat(64)),
                    java.util.Map.entry("releaseVerification", "PASS"),
                    java.util.Map.entry("schemaGeneration", "PASS"),
                    java.util.Map.entry("retention", "PASS"),
                    java.util.Map.entry("currentLinkFingerprint", "5".repeat(64)),
                    java.util.Map.entry("previousLinkFingerprint", "6".repeat(64)),
                    java.util.Map.entry("linksChanged", false),
                    java.util.Map.entry("valuesExposed", false)));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/codex/update/activate", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.ofEntries(
                    java.util.Map.entry("schemaVersion", "codex-update-activate-v1"),
                    java.util.Map.entry("operation", request.get("operation").asText()),
                    java.util.Map.entry("workerId", "ax42-01"),
                    java.util.Map.entry("planId", request.get("planId").asText()),
                    java.util.Map.entry("candidateId", request.get("candidateId").asText()),
                    java.util.Map.entry("authorizationId", request.get("authorizationId").asText()),
                    java.util.Map.entry("idempotencyKey", request.get("idempotencyKey").asText()),
                    java.util.Map.entry("state", "ACTIVATED"),
                    java.util.Map.entry("codexVersion", "0.146.0"),
                    java.util.Map.entry("releaseDigestSha256", "1".repeat(64)),
                    java.util.Map.entry("catalogRevision", "2".repeat(64)),
                    java.util.Map.entry("schemaComparison", "PASS"),
                    java.util.Map.entry("focusedContracts", "PASS"),
                    java.util.Map.entry("workerHealth", "PASS"),
                    java.util.Map.entry("canary", "PASS"),
                    java.util.Map.entry("currentBeforeFingerprint", "3".repeat(64)),
                    java.util.Map.entry("previousBeforeFingerprint", "4".repeat(64)),
                    java.util.Map.entry("currentAfterFingerprint", "5".repeat(64)),
                    java.util.Map.entry("previousAfterFingerprint", "6".repeat(64)),
                    java.util.Map.entry("automaticRestore", "NOT_REQUIRED"),
                    java.util.Map.entry("valuesExposed", false)));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/codex/update/rollback", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.ofEntries(
                    java.util.Map.entry("schemaVersion", "codex-update-rollback-v1"),
                    java.util.Map.entry("operation", request.get("operation").asText()),
                    java.util.Map.entry("workerId", "ax42-01"),
                    java.util.Map.entry("planId", request.get("planId").asText()),
                    java.util.Map.entry("candidateId", request.get("candidateId").asText()),
                    java.util.Map.entry("activationId", request.get("activationId").asText()),
                    java.util.Map.entry("authorizationId", request.get("authorizationId").asText()),
                    java.util.Map.entry("idempotencyKey", request.get("idempotencyKey").asText()),
                    java.util.Map.entry("state", "ROLLED_BACK"),
                    java.util.Map.entry("linkRestore", "PASS"),
                    java.util.Map.entry("workerServiceRestart", "PASS"),
                    java.util.Map.entry("affectedServices",
                            java.util.List.of("atenea-agent-run-worker-v1.service")),
                    java.util.Map.entry("appServerServicesRestarted", 0),
                    java.util.Map.entry("currentBeforeFingerprint", "1".repeat(64)),
                    java.util.Map.entry("previousBeforeFingerprint", "2".repeat(64)),
                    java.util.Map.entry("currentAfterFingerprint", "3".repeat(64)),
                    java.util.Map.entry("previousAfterFingerprint", "4".repeat(64)),
                    java.util.Map.entry("valuesExposed", false)));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        properties = new RemoteWorkerProperties();
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTokenFile(token.toString());
        attachmentManifestService = mock(ProjectCodexAttachmentManifestService.class);
        client = new RemoteWorkerClient(properties, objectMapper, attachmentManifestService);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void projectDispatchUsesOnlyPersistedExactIdentityAndContinuesThread() {
        UUID threadId = UUID.fromString("bcf43e2e-c9e8-42df-96b2-e9183462c2f4");
        AgentRunEntity run = projectRun(threadId.toString());

        RemoteWorkerClient.Execution response = client.dispatch(
                run, "Update only the accepted documentation fixture.");

        JsonNode body = requestBody.get();
        JsonNode workload = body.get("workload");
        assertEquals(run.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(ProjectCodexIdentity.WORKLOAD_KIND, workload.get("kind").asText());
        assertEquals(ProjectCodexIdentity.PROJECT_IDENTITY, workload.get("projectId").asText());
        assertEquals(ProjectCodexIdentity.REPOSITORY, workload.get("repository").asText());
        assertEquals(ProjectCodexIdentity.BRANCH, workload.get("branch").asText());
        assertEquals(TEST_CANONICAL_COMMIT, workload.get("commit").asText());
        assertEquals(ProjectCodexIdentity.MANIFEST_SHA256, workload.get("manifestSha256").asText());
        assertEquals(ReviewedInstructionBundleIdentity.REVISION,
                workload.get("instructionBundleRevision").asText());
        assertEquals(ReviewedInstructionBundleIdentity.ATENEA_BUNDLE_SHA256,
                workload.get("instructionBundleSha256").asText());
        assertEquals(ReviewedInstructionBundleIdentity.PLATFORM_SHA256,
                workload.get("platformInstructionSha256").asText());
        assertEquals(ReviewedInstructionBundleIdentity.PROJECT_PATH,
                workload.get("projectInstructionPath").asText());
        assertEquals(ReviewedInstructionBundleIdentity.ATENEA_PROJECT_SHA256,
                workload.get("projectInstructionSha256").asText());
        assertEquals(threadId.toString(), workload.get("threadId").asText());
        assertEquals(13, workload.size());
        assertEquals(1, response.progressEvents().size());
        assertEquals(1, response.progressEvents().getFirst().sequence());
        assertEquals("ACCEPTED", response.progressEvents().getFirst().category());
        assertNull(workload.get("command"));
        assertNull(workload.get("image"));
        assertNull(workload.get("composeFile"));
        assertNull(workload.get("path"));
        assertNull(workload.get("host"));
        assertNull(workload.get("slot"));
        assertNull(workload.get("endpoint"));
        assertNull(workload.get("environment"));
        assertNull(workload.get("credential"));
        assertNull(workload.get("ruleSource"));
    }

    @Test
    void fetchesStrictWorkerCodexCatalog() {
        RemoteWorkerClient.CodexCatalog catalog = client.codexCatalog();

        assertEquals("codex-model-catalog-v1", catalog.schemaVersion());
        assertEquals("125b9437e38f83e04cb10996fc70d3ab44c32082009b8e897cb08bb340b13187",
                catalog.catalogRevision());
        assertEquals("0.145.0", catalog.codexVersion());
        assertEquals("gpt-5.6-sol", catalog.models().getFirst().modelId());
        assertEquals(java.util.List.of("none", "low", "medium", "high", "xhigh", "max"),
                catalog.models().getFirst().supportedEfforts());
    }

    @Test
    void profiledProjectDispatchUsesOnlyClosedV2Fields() {
        AgentRunEntity run = projectRun("bcf43e2e-c9e8-42df-96b2-e9183462c2f4");
        run.setCodexModelId("gpt-5.6-sol");
        run.setCodexReasoningEffort(
                com.atenea.persistence.worksession.CodexReasoningEffort.HIGH);
        run.setCodexCatalogRevision(
                "125b9437e38f83e04cb10996fc70d3ab44c32082009b8e897cb08bb340b13187");
        run.setCodexVersion("0.145.0");

        client.dispatch(run, "Inspect the accepted project.");

        JsonNode workload = requestBody.get().get("workload");
        assertEquals("project-codex-v2", workload.get("kind").asText());
        assertEquals("gpt-5.6-sol", workload.get("modelId").asText());
        assertEquals("high", workload.get("reasoningEffort").asText());
        assertEquals(run.getCodexCatalogRevision(), workload.get("catalogRevision").asText());
        assertEquals("0.145.0", workload.get("codexVersion").asText());
        assertEquals(17, workload.size());
        assertNull(workload.get("command"));
        assertNull(workload.get("provider"));
        assertNull(workload.get("endpoint"));
    }

    @Test
    void profiledImageDispatchUsesOnlyOrderedClosedV3ReferencesInFingerprint() {
        AgentRunEntity run = projectRun("bcf43e2e-c9e8-42df-96b2-e9183462c2f4");
        run.setSelectedWorkerId("ax42-01");
        run.setWorkloadKind(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND);
        run.setCodexModelId("gpt-5.6-sol");
        run.setCodexReasoningEffort(
                com.atenea.persistence.worksession.CodexReasoningEffort.HIGH);
        run.setCodexCatalogRevision(
                "125b9437e38f83e04cb10996fc70d3ab44c32082009b8e897cb08bb340b13187");
        run.setCodexVersion("0.145.0");
        run.setAttachmentCount(2);
        run.setAttachmentBytes(3072L);
        run.setAttachmentManifestSha256("c".repeat(64));
        UUID firstId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
        UUID secondId = UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d");
        ProjectCodexAttachmentManifestService.AttachmentReference first =
                new ProjectCodexAttachmentManifestService.AttachmentReference(
                        firstId, "image/png", 1024L, "a".repeat(64));
        ProjectCodexAttachmentManifestService.AttachmentReference second =
                new ProjectCodexAttachmentManifestService.AttachmentReference(
                        secondId, "image/webp", 2048L, "b".repeat(64));
        when(attachmentManifestService.exactReferences(run))
                .thenReturn(List.of(first, second), List.of(second, first));

        client.dispatch(run, "Inspect both accepted images.");
        JsonNode firstWorkload = requestBody.get().get("workload").deepCopy();
        client.dispatch(run, "Inspect both accepted images.");
        JsonNode reorderedWorkload = requestBody.get().get("workload").deepCopy();

        assertEquals(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND,
                firstWorkload.get("kind").asText());
        assertEquals(18, firstWorkload.size());
        JsonNode attachments = firstWorkload.get("attachments");
        assertEquals(2, attachments.size());
        assertEquals(firstId.toString(), attachments.get(0).get("attachmentId").asText());
        assertEquals("image/png", attachments.get(0).get("contentType").asText());
        assertEquals(1024L, attachments.get(0).get("sizeBytes").asLong());
        assertEquals("a".repeat(64), attachments.get(0).get("sha256").asText());
        assertEquals(java.util.Set.of("attachmentId", "contentType", "sizeBytes", "sha256"),
                objectMapper.convertValue(attachments.get(0), java.util.Map.class).keySet());
        for (String forbidden : List.of(
                "filename", "bytes", "content", "storageIdentity", "path", "url",
                "workerId", "workspaceIdentity", "remoteSessionId", "options")) {
            assertNull(attachments.get(0).get(forbidden));
            assertNull(attachments.get(1).get(forbidden));
        }
        for (String forbidden : List.of(
                "command", "provider", "endpoint", "path", "host", "slot",
                "environment", "credential", "storageIdentity", "attachmentBytes")) {
            assertNull(firstWorkload.get(forbidden));
        }
        assertNotEquals(sha256(firstWorkload), sha256(reorderedWorkload));
    }

    @Test
    void explicitImageContinuationsNeverLeakTheLatestImageIntoTextOnlyWorkload() {
        String stableThreadId = "bcf43e2e-c9e8-42df-96b2-e9183462c2f4";
        AgentRunEntity first = profiledImageRun(null);
        AgentRunEntity second = profiledImageRun(stableThreadId);
        AgentRunEntity textOnly = projectRun(stableThreadId);
        applyProfile(textOnly);
        UUID firstId = UUID.fromString("4e8f351e-e05a-41b6-99e5-3eb72d770002");
        UUID secondId = UUID.fromString("9aa2c7e5-1fd9-48ec-aa10-03dfdfb8ca7d");
        ProjectCodexAttachmentManifestService.AttachmentReference firstReference =
                new ProjectCodexAttachmentManifestService.AttachmentReference(
                        firstId, "image/png", 1024L, "a".repeat(64));
        ProjectCodexAttachmentManifestService.AttachmentReference secondReference =
                new ProjectCodexAttachmentManifestService.AttachmentReference(
                        secondId, "image/webp", 2048L, "b".repeat(64));
        when(attachmentManifestService.exactReferences(first)).thenReturn(List.of(firstReference));
        when(attachmentManifestService.exactReferences(second)).thenReturn(List.of(secondReference));

        client.dispatch(first, "Inspect the first accepted image.");
        JsonNode firstRequest = requestBody.get().deepCopy();
        client.dispatch(second, "Inspect the second accepted image in the same thread.");
        JsonNode secondRequest = requestBody.get().deepCopy();
        client.dispatch(textOnly, "Continue in the same thread without an image.");
        JsonNode textRequest = requestBody.get().deepCopy();

        assertEquals(firstRequest.get("sessionId"), secondRequest.get("sessionId"));
        assertEquals(firstRequest.get("sessionId"), textRequest.get("sessionId"));
        assertEquals(firstRequest.get("workspaceIdentity"), secondRequest.get("workspaceIdentity"));
        assertEquals(firstRequest.get("workspaceIdentity"), textRequest.get("workspaceIdentity"));

        JsonNode firstWorkload = firstRequest.get("workload");
        JsonNode secondWorkload = secondRequest.get("workload");
        JsonNode textWorkload = textRequest.get("workload");
        assertEquals(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND, firstWorkload.get("kind").asText());
        assertEquals(1, firstWorkload.get("attachments").size());
        assertEquals(firstId.toString(),
                firstWorkload.get("attachments").get(0).get("attachmentId").asText());
        assertEquals(true, firstWorkload.get("threadId").isNull());

        assertEquals(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND, secondWorkload.get("kind").asText());
        assertEquals(stableThreadId, secondWorkload.get("threadId").asText());
        assertEquals(1, secondWorkload.get("attachments").size());
        assertEquals(secondId.toString(),
                secondWorkload.get("attachments").get(0).get("attachmentId").asText());
        assertNotEquals(firstId.toString(),
                secondWorkload.get("attachments").get(0).get("attachmentId").asText());

        assertEquals("project-codex-v2", textWorkload.get("kind").asText());
        assertEquals(stableThreadId, textWorkload.get("threadId").asText());
        assertNull(textWorkload.get("attachments"));
        assertNull(textWorkload.get("attachmentId"));
        assertNull(textWorkload.get("image"));
        assertNull(textWorkload.get("path"));
    }

    @Test
    void imageRunUsesTheSameExactProjectWorkspaceAuthorityAsV1AndV2() {
        AgentRunEntity run = projectRun(null);
        run.setWorkloadKind(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND);
        run.getSession().setWorkspaceBranch("work-session/image-fixture");

        RemoteWorkerClient.Workspace workspace = client.ensureWorkspace(run);

        assertEquals("ready", workspace.state());
        assertEquals(run.getRemoteSessionId().toString(), workspace.sessionId());
        assertEquals(run.getWorkspaceIdentity(), workspace.workspaceIdentity());
        assertEquals(ProjectCodexIdentity.PROJECT_IDENTITY, workspace.projectId());
        assertEquals(TEST_CANONICAL_COMMIT, workspace.canonicalCommit());
    }

    @Test
    void workspaceReleaseUsesOnlyPersistedExactAteneaIdentityAndValidatesReceipt() {
        WorkSessionEntity session = releasableSession();

        RemoteWorkerClient.WorkspaceRelease result = client.releaseWorkspace(session);

        JsonNode body = requestBody.get();
        String operationId = session.getRemoteCloseOperationId().toString();
        assertEquals(Set.of(
                        "operationId", "idempotencyKey", "sessionId", "workspaceIdentity",
                        "projectId", "repository", "branch", "commit", "manifestSha256",
                        "workspaceBranch"),
                objectMapper.convertValue(body, Map.class).keySet());
        assertEquals(operationId, body.get("operationId").asText());
        assertEquals(operationId, body.get("idempotencyKey").asText());
        assertEquals(operationId, releaseIdempotencyHeader.get());
        assertEquals(session.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(session.getWorkspaceIdentity(), body.get("workspaceIdentity").asText());
        assertEquals(ProjectCodexIdentity.PROJECT_IDENTITY, body.get("projectId").asText());
        assertEquals(ProjectCodexIdentity.REPOSITORY, body.get("repository").asText());
        assertEquals(ProjectCodexIdentity.BRANCH, body.get("branch").asText());
        assertEquals(TEST_CANONICAL_COMMIT, body.get("commit").asText());
        assertEquals(ProjectCodexIdentity.MANIFEST_SHA256,
                body.get("manifestSha256").asText());
        assertEquals(session.getWorkspaceBranch(), body.get("workspaceBranch").asText());
        for (String forbidden : List.of(
                "command", "path", "slot", "port", "service", "endpoint",
                "resourceName", "label", "credential", "deletionTarget")) {
            assertNull(body.get(forbidden));
        }
        assertEquals("project-workspace-release-v1", result.schemaVersion());
        assertEquals("RELEASED", result.state());
        assertEquals(operationId, result.operationId());
        assertEquals("205ff648ddd1d736a40c92e695d395c565f006ec245ee0de291629bcb2b903b7",
                result.requestFingerprintSha256());
        assertEquals(6, result.revision());
        assertEquals(true, result.retained().values().stream().allMatch(Boolean.TRUE::equals));
        assertEquals(false, result.valuesExposed());
    }

    @Test
    void workspaceReleaseRejectsIncompleteOrForeignPersistedOwnershipBeforeNetwork() {
        List<Consumer<WorkSessionEntity>> invalidators = List.of(
                value -> value.setExecutionTarget(ExecutionTarget.LOCAL),
                value -> value.setSelectedWorkerId("foreign-worker"),
                value -> value.setWorkspaceIdentity(
                        "remote:foreign:work-session:" + value.getRemoteSessionId()),
                value -> value.setWorkspaceBranch("atenea/session-foreign"),
                value -> value.setRemoteCloseOperationId(null),
                value -> value.setRemoteCloseState(RemoteCloseState.NOT_STARTED),
                value -> value.getProject().setName(BeautipsProjectCodexIdentity.PROJECT_NAME));

        for (Consumer<WorkSessionEntity> invalidator : invalidators) {
            requestBody.set(null);
            WorkSessionEntity session = releasableSession();
            invalidator.accept(session);

            RemoteWorkerException exception = assertThrows(
                    RemoteWorkerException.class,
                    () -> client.releaseWorkspace(session));

            assertEquals(409, exception.getStatusCode());
            assertNull(requestBody.get());
        }
    }

    @Test
    void workspaceReleaseRejectsUnsealedUnknownOrUnsafeReceiptProjection() {
        List<Consumer<Map<String, Object>>> mutations = List.of(
                receipt -> {
                    receipt.put("workerId", "foreign-worker");
                    sealWorkspaceReleaseReceipt(receipt);
                },
                receipt -> {
                    Map<String, Object> retained = new java.util.LinkedHashMap<>(
                            (Map<String, Object>) receipt.get("retained"));
                    retained.put("git", false);
                    receipt.put("retained", retained);
                    sealWorkspaceReleaseReceipt(receipt);
                },
                receipt -> {
                    receipt.put("path", "/srv/foreign");
                    sealWorkspaceReleaseReceipt(receipt);
                },
                receipt -> {
                    receipt.put("revision", 5);
                    sealWorkspaceReleaseReceipt(receipt);
                },
                receipt -> {
                    Map<String, Object> released = new java.util.LinkedHashMap<>(
                            (Map<String, Object>) receipt.get("released"));
                    released.put("allocation", false);
                    receipt.put("released", released);
                    sealWorkspaceReleaseReceipt(receipt);
                },
                receipt -> receipt.put("receiptSha256", "0".repeat(64)));

        for (Consumer<Map<String, Object>> mutation : mutations) {
            releaseReceiptMutation.set(mutation);

            RemoteWorkerException exception = assertThrows(
                    RemoteWorkerException.class,
                    () -> client.releaseWorkspace(releasableSession()));

            assertEquals(502, exception.getStatusCode());
            assertEquals("REMOTE_WORKER_PROTOCOL_FAILURE", exception.getFailureCode());
            assertEquals(RemoteWorkerFailureCategory.PROTOCOL, exception.getCategory());
            assertEquals(false, exception.isRetryable());
        }
        releaseReceiptMutation.set(null);
    }

    @Test
    void codexUpdateStageSendsOnlyPersistedClosedIdentities() {
        UUID planId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        RemoteWorkerClient.CodexUpdateStage result = client.stageCodexUpdate(
                planId, candidateId, idempotencyKey);

        JsonNode body = requestBody.get();
        assertEquals(java.util.Set.of(
                "operation", "planId", "candidateId", "idempotencyKey"),
                objectMapper.convertValue(body, java.util.Map.class).keySet());
        assertEquals("STAGE_CODEX_UPDATE", body.get("operation").asText());
        assertEquals(planId.toString(), body.get("planId").asText());
        assertEquals(candidateId.toString(), body.get("candidateId").asText());
        assertEquals(idempotencyKey.toString(), body.get("idempotencyKey").asText());
        assertEquals("STAGED", result.state());
        assertEquals("PASS", result.schemaGeneration());
        assertEquals(false, result.linksChanged());
        assertEquals(false, result.valuesExposed());
        assertNull(body.get("releaseUrl"));
        assertNull(body.get("version"));
        assertNull(body.get("path"));
        assertNull(body.get("command"));
        assertNull(body.get("service"));
    }

    @Test
    void codexUpdateActivationSendsOnlyExactAuthorizationAndPersistedIdentities() {
        UUID planId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        RemoteWorkerClient.CodexUpdateActivation result = client.activateCodexUpdate(
                planId, candidateId, authorizationId, idempotencyKey);

        JsonNode body = requestBody.get();
        assertEquals(java.util.Set.of(
                "operation", "planId", "candidateId", "authorizationId", "idempotencyKey"),
                objectMapper.convertValue(body, java.util.Map.class).keySet());
        assertEquals("ACTIVATE_CODEX_UPDATE", body.get("operation").asText());
        assertEquals(authorizationId.toString(), body.get("authorizationId").asText());
        assertEquals("ACTIVATED", result.state());
        assertEquals("PASS", result.focusedContracts());
        assertEquals("PASS", result.workerHealth());
        assertEquals("PASS", result.canary());
        assertEquals(false, result.valuesExposed());
        assertNull(body.get("version"));
        assertNull(body.get("path"));
        assertNull(body.get("command"));
        assertNull(body.get("host"));
        assertNull(body.get("service"));
    }

    @Test
    void codexUpdateRollbackSendsOnlyPersistedIdentitiesAndNoServiceAuthority() {
        UUID planId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID activationId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        RemoteWorkerClient.CodexUpdateRollback result = client.rollbackCodexUpdate(
                planId, candidateId, activationId, authorizationId, idempotencyKey);

        JsonNode body = requestBody.get();
        assertEquals(java.util.Set.of("operation", "planId", "candidateId", "activationId",
                        "authorizationId", "idempotencyKey"),
                objectMapper.convertValue(body, java.util.Map.class).keySet());
        assertEquals("ROLLBACK_CODEX_UPDATE", body.get("operation").asText());
        assertEquals(activationId.toString(), body.get("activationId").asText());
        assertEquals("ROLLED_BACK", result.state());
        assertEquals(java.util.List.of("atenea-agent-run-worker-v1.service"),
                result.affectedServices());
        assertEquals(0, result.appServerServicesRestarted());
        assertNull(body.get("service"));
        assertNull(body.get("host"));
        assertNull(body.get("command"));
        assertNull(body.get("path"));
    }

    @Test
    void beautipsDispatchReusesPersistedIdentityAndContinuesThread() {
        UUID threadId = UUID.fromString("bcf43e2e-c9e8-42df-96b2-e9183462c2f4");
        AgentRunEntity run = beautipsRun(threadId.toString());

        client.dispatch(run, "Update only the accepted Beautips fixture.");

        JsonNode body = requestBody.get();
        JsonNode workload = body.get("workload");
        assertEquals(run.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(run.getWorkspaceIdentity(), body.get("workspaceIdentity").asText());
        assertEquals(ProjectCodexIdentity.WORKLOAD_KIND, workload.get("kind").asText());
        assertEquals(BeautipsProjectCodexIdentity.PROJECT_IDENTITY, workload.get("projectId").asText());
        assertEquals(BeautipsProjectCodexIdentity.REPOSITORY, workload.get("repository").asText());
        assertEquals(BeautipsProjectCodexIdentity.BRANCH, workload.get("branch").asText());
        assertEquals(BeautipsProjectCodexIdentity.COMMIT, workload.get("commit").asText());
        assertEquals(BeautipsProjectCodexIdentity.MANIFEST_SHA256, workload.get("manifestSha256").asText());
        assertEquals(ReviewedInstructionBundleIdentity.BEAUTIPS_BUNDLE_SHA256,
                workload.get("instructionBundleSha256").asText());
        assertEquals(ReviewedInstructionBundleIdentity.BEAUTIPS_PROJECT_SHA256,
                workload.get("projectInstructionSha256").asText());
        assertEquals(threadId.toString(), workload.get("threadId").asText());
        assertNull(workload.get("command"));
        assertNull(workload.get("path"));
        assertNull(workload.get("endpoint"));
        assertNull(workload.get("environment"));
    }

    @Test
    void beautipsWorkspaceEnsureUsesOnlyPersistedExactIdentity() {
        AgentRunEntity run = beautipsRun(null);
        run.getSession().setWorkspaceBranch("atenea/session-11111111-1111-4111-8111-111111111111");

        RemoteWorkerClient.Workspace workspace = client.ensureWorkspace(run);

        JsonNode body = requestBody.get();
        assertEquals(run.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(run.getWorkspaceIdentity(), body.get("workspaceIdentity").asText());
        assertEquals(run.getRepositoryCommit(), workspace.canonicalCommit());
        assertEquals(BeautipsProjectCodexIdentity.PROJECT_IDENTITY, body.get("projectId").asText());
        assertEquals(BeautipsProjectCodexIdentity.REPOSITORY, body.get("repository").asText());
        assertEquals(BeautipsProjectCodexIdentity.BRANCH, body.get("branch").asText());
        assertEquals(BeautipsProjectCodexIdentity.COMMIT, body.get("commit").asText());
        assertEquals(BeautipsProjectCodexIdentity.MANIFEST_SHA256, body.get("manifestSha256").asText());
        assertEquals(
                "atenea/session-11111111-1111-4111-8111-111111111111",
                body.get("workspaceBranch").asText());
        assertNull(body.get("command"));
        assertNull(body.get("path"));
        assertNull(body.get("endpoint"));
        assertNull(body.get("environment"));
        assertEquals("ready", workspace.state());
        assertEquals(false, workspace.valuesExposed());
    }

    @Test
    void ateneaWorkspaceEnsureUsesOnlyPersistedExactIdentity() {
        AgentRunEntity run = projectRun(null);
        run.getSession().setWorkspaceBranch("atenea/session-11111111-1111-4111-8111-111111111111");

        RemoteWorkerClient.Workspace workspace = client.ensureWorkspace(run);

        JsonNode body = requestBody.get();
        assertEquals(run.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(run.getWorkspaceIdentity(), body.get("workspaceIdentity").asText());
        assertEquals(run.getRepositoryCommit(), workspace.canonicalCommit());
        assertEquals(ProjectCodexIdentity.PROJECT_IDENTITY, body.get("projectId").asText());
        assertEquals(ProjectCodexIdentity.REPOSITORY, body.get("repository").asText());
        assertEquals(ProjectCodexIdentity.BRANCH, body.get("branch").asText());
        assertEquals(TEST_CANONICAL_COMMIT, body.get("commit").asText());
        assertEquals(ProjectCodexIdentity.MANIFEST_SHA256, body.get("manifestSha256").asText());
        assertEquals("ready", workspace.state());
        assertEquals(false, workspace.valuesExposed());
    }

    @Test
    void capacityOwnerDiagnosisUsesOnlyPersistedCandidateIdentity() {
        WorkSessionEntity session = releasableSession();

        RemoteWorkerClient.WorkspaceCapacityOwner diagnosis =
                client.diagnoseWorkspaceCapacityOwner(session);

        JsonNode body = requestBody.get();
        assertEquals(session.getRemoteSessionId().toString(),
                body.get("sessionId").asText());
        assertEquals(session.getWorkspaceIdentity(),
                body.get("workspaceIdentity").asText());
        assertEquals(ProjectCodexIdentity.PROJECT_IDENTITY,
                body.get("projectId").asText());
        assertEquals(ProjectCodexIdentity.REPOSITORY,
                body.get("repository").asText());
        assertEquals(ProjectCodexIdentity.BRANCH,
                body.get("branch").asText());
        assertEquals(session.getCanonicalSourceCommit(),
                body.get("commit").asText());
        for (String forbidden : List.of(
                "command", "path", "slot", "port", "service", "endpoint",
                "label", "credential", "resource")) {
            assertNull(body.get(forbidden));
        }
        assertEquals("OWNED", diagnosis.state());
        assertEquals(false, diagnosis.valuesExposed());
    }

    @Test
    void historicalOwnerUsesExactLaterCanonicalWitnessWithoutChangingAuthority() {
        WorkSessionEntity owner = releasableSession();
        owner.setId(16L);
        owner.setCreatedAt(Instant.parse("2026-08-03T10:00:00Z"));
        clearCanonicalSourceObservation(owner);
        WorkSessionEntity witness = releasableSession();
        UUID witnessRemoteId = UUID.fromString(
                "18c00753-6080-42f7-ac05-18c47b236cac");
        witness.setId(17L);
        witness.setRemoteSessionId(witnessRemoteId);
        witness.setWorkspaceIdentity(
                "remote:ax42-01:work-session:" + witnessRemoteId);
        witness.setWorkspaceBranch("atenea/session-" + witnessRemoteId);
        witness.setCanonicalSourceCommit("c".repeat(40));
        witness.setCreatedAt(Instant.parse("2026-08-03T10:03:00Z"));

        RemoteWorkerClient.WorkspaceCapacityOwner diagnosis =
                client.diagnoseWorkspaceCapacityOwner(owner, witness);

        JsonNode body = requestBody.get();
        assertEquals(owner.getRemoteSessionId().toString(),
                body.get("sessionId").asText());
        assertEquals(owner.getWorkspaceIdentity(),
                body.get("workspaceIdentity").asText());
        assertEquals(witness.getCanonicalSourceCommit(),
                body.get("commit").asText());
        assertEquals("OWNED", diagnosis.state());

        RemoteWorkerClient.WorkspaceRelease release =
                client.releaseWorkspace(owner, witness);

        assertEquals(witness.getCanonicalSourceCommit(),
                requestBody.get().get("commit").asText());
        assertEquals("RELEASED", release.state());
    }

    @Test
    void releasePreflightUsesTheExactReleaseRequestWithoutMutationAuthority() {
        WorkSessionEntity session = releasableSession();

        RemoteWorkerClient.WorkspaceReleasePreflight diagnosis =
                client.diagnoseWorkspaceReleasePreflight(session, session);

        JsonNode body = requestBody.get();
        String operationId = session.getRemoteCloseOperationId().toString();
        assertEquals(operationId, body.get("operationId").asText());
        assertEquals(operationId, body.get("idempotencyKey").asText());
        assertEquals(operationId, releaseIdempotencyHeader.get());
        assertEquals(session.getRemoteSessionId().toString(),
                body.get("sessionId").asText());
        assertEquals(session.getWorkspaceIdentity(),
                body.get("workspaceIdentity").asText());
        assertEquals(session.getCanonicalSourceCommit(),
                body.get("commit").asText());
        for (String forbidden : List.of(
                "command", "path", "slot", "port", "service", "endpoint",
                "label", "credential", "resource")) {
            assertNull(body.get(forbidden));
        }
        assertEquals("PREFLIGHT_ACCEPTED", diagnosis.state());
        assertEquals(operationId, diagnosis.operationId());
        assertEquals(false, diagnosis.valuesExposed());
    }

    @Test
    void historicalOwnerRejectsPartialCanonicalHistoryBeforeNetwork() {
        WorkSessionEntity owner = releasableSession();
        owner.setId(16L);
        owner.setCreatedAt(Instant.parse("2026-08-03T10:00:00Z"));
        clearCanonicalSourceObservation(owner);
        owner.setCanonicalSourceRef("refs/heads/main");
        WorkSessionEntity witness = releasableSession();
        witness.setId(17L);
        witness.setCreatedAt(Instant.parse("2026-08-03T10:03:00Z"));
        requestBody.set(null);

        RemoteWorkerException exception = assertThrows(
                RemoteWorkerException.class,
                () -> client.diagnoseWorkspaceCapacityOwner(owner, witness));

        assertEquals(409, exception.getStatusCode());
        assertNull(requestBody.get());
    }

    @Test
    void typedWorkerRejectionPreservesOnlyValidatedSafeProjection() throws Exception {
        UUID blocker = UUID.fromString("22222222-2222-4222-8222-222222222222");
        workerErrorResponse.set(objectMapper.writeValueAsBytes(java.util.Map.of(
                "schemaVersion", "worker-error-v1",
                "code", "NORMAL_CAPACITY_EXHAUSTED",
                "category", "CAPACITY",
                "retryable", true,
                "nextAction", "WAIT",
                "blockerSessionId", blocker.toString())));

        RemoteWorkerException exception = assertThrows(
                RemoteWorkerException.class,
                () -> client.ensureWorkspace(workspaceRun()));

        assertEquals(409, exception.getStatusCode());
        assertEquals("NORMAL_CAPACITY_EXHAUSTED", exception.getFailureCode());
        assertEquals(RemoteWorkerFailureCategory.CAPACITY, exception.getCategory());
        assertEquals(true, exception.isRetryable());
        assertEquals(
                com.atenea.persistence.worksession.AgentRunRecoveryNextAction.WAIT,
                exception.getNextAction());
        assertEquals(blocker, exception.getBlockerSessionId());
        assertEquals(true, exception.hasTypedFailure());
        assertNull(exception.getCause());
    }

    @Test
    void incompatibleStatusCategoryOrRetryProjectionBecomesProtocolFailure() throws Exception {
        List<Map<String, Object>> incompatible = List.of(
                Map.of(
                        "status", 403,
                        "category", "TRANSPORT",
                        "retryable", true,
                        "nextAction", "REQUEST_RECONCILIATION"),
                Map.of(
                        "status", 503,
                        "category", "VALIDATION",
                        "retryable", false,
                        "nextAction", "NONE"),
                Map.of(
                        "status", 409,
                        "category", "POLICY",
                        "retryable", true,
                        "nextAction", "WAIT"),
                Map.of(
                        "status", 600,
                        "category", "VALIDATION",
                        "retryable", false,
                        "nextAction", "NONE"));

        for (Map<String, Object> values : incompatible) {
            workerErrorStatus.set((Integer) values.get("status"));
            workerErrorResponse.set(objectMapper.writeValueAsBytes(Map.of(
                    "schemaVersion", "worker-error-v1",
                    "code", "INCOMPATIBLE_FAILURE_PROJECTION",
                    "category", values.get("category"),
                    "retryable", values.get("retryable"),
                    "nextAction", values.get("nextAction"))));

            RemoteWorkerException exception = assertThrows(
                    RemoteWorkerException.class,
                    () -> client.ensureWorkspace(workspaceRun()));

            assertEquals("REMOTE_WORKER_PROTOCOL_FAILURE", exception.getFailureCode());
            assertEquals(RemoteWorkerFailureCategory.PROTOCOL, exception.getCategory());
            assertEquals(false, exception.isRetryable());
        }
    }

    @Test
    void malformedUnsafeOrOversizedWorkerRejectionBecomesGenericProtocolFailure()
            throws Exception {
        String marker = "unsafe-worker-body-marker";
        java.util.List<byte[]> rejectedBodies = java.util.List.of(
                objectMapper.writeValueAsBytes(java.util.Map.of(
                        "schemaVersion", "worker-error-v1",
                        "code", "NORMAL_CAPACITY_EXHAUSTED",
                        "category", "CAPACITY",
                        "retryable", true,
                        "nextAction", "WAIT",
                        "detail", marker)),
                objectMapper.writeValueAsBytes(java.util.Map.of(
                        "schemaVersion", "worker-error-v1",
                        "code", "NORMAL_CAPACITY_EXHAUSTED",
                        "category", "CAPACITY",
                        "retryable", true,
                        "nextAction", "WAIT",
                        "blockerSessionId", "not-a-uuid")),
                objectMapper.writeValueAsBytes(java.util.Map.of(
                        "schemaVersion", "unknown-error-v1",
                        "code", "NORMAL_CAPACITY_EXHAUSTED",
                        "category", "CAPACITY",
                        "retryable", true,
                        "nextAction", "WAIT")),
                objectMapper.writeValueAsBytes(java.util.Map.of(
                        "schemaVersion", "worker-error-v1",
                        "code", "NORMAL_CAPACITY_EXHAUSTED",
                        "category", "CAPACITY",
                        "retryable", true,
                        "nextAction", "RECONCILE_REMOTE_CLOSE")),
                "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ("{" + marker.repeat(200) + "}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        for (byte[] rejectedBody : rejectedBodies) {
            workerErrorResponse.set(rejectedBody);
            RemoteWorkerException exception = assertThrows(
                    RemoteWorkerException.class,
                    () -> client.ensureWorkspace(workspaceRun()));
            assertEquals(409, exception.getStatusCode());
            assertEquals("REMOTE_WORKER_PROTOCOL_FAILURE", exception.getFailureCode());
            assertEquals(RemoteWorkerFailureCategory.PROTOCOL, exception.getCategory());
            assertEquals(false, exception.isRetryable());
            assertEquals(
                    com.atenea.persistence.worksession.AgentRunRecoveryNextAction
                            .CONTACT_PLATFORM_ADMINISTRATOR,
                    exception.getNextAction());
            assertNull(exception.getBlockerSessionId());
            assertEquals(false, exception.getMessage().contains(marker));
        }

        workerErrorContentType.set("text/plain");
        workerErrorResponse.set(marker.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RemoteWorkerException exception = assertThrows(
                RemoteWorkerException.class,
                () -> client.ensureWorkspace(workspaceRun()));
        assertEquals("REMOTE_WORKER_PROTOCOL_FAILURE", exception.getFailureCode());
        assertEquals(false, exception.getMessage().contains(marker));
    }

    @Test
    void firstProjectDispatchSendsExplicitNullThread() {
        client.dispatch(projectRun(null), "First turn.");

        assertEquals(true, requestBody.get().get("workload").get("threadId").isNull());
    }

    @Test
    void retainedDraftFingerprintUsesOnlyPersistedOwnershipAndAcceptedObservation() {
        AgentRunEntity run = projectRun(null);
        WorkSessionEntity session = run.getSession();
        session.setWorkspaceIdentity(run.getWorkspaceIdentity());

        RemoteWorkerClient.DraftFingerprint fingerprint = client.fingerprintRetainedDraft(session);

        JsonNode body = requestBody.get();
        assertEquals(run.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(run.getWorkspaceIdentity(), body.get("workspaceIdentity").asText());
        assertEquals(ProjectCodexIdentity.PROJECT_IDENTITY, body.get("projectId").asText());
        assertEquals(ProjectCodexIdentity.REPOSITORY, body.get("repository").asText());
        assertEquals(ProjectCodexIdentity.BRANCH, body.get("branch").asText());
        assertEquals(TEST_CANONICAL_COMMIT, body.get("acceptedCommit").asText());
        assertEquals(ProjectCodexIdentity.MANIFEST_SHA256, body.get("manifestSha256").asText());
        assertNull(body.get("path"));
        assertNull(body.get("command"));
        assertEquals("3".repeat(64), fingerprint.fingerprintSha256());
        assertEquals(false, fingerprint.valuesExposed());
    }

    @Test
    void sourceTreeFingerprintUsesOnlyPersistedOwnershipAndReturnsNoValues() {
        AgentRunEntity run = projectRun(null);
        WorkSessionEntity session = run.getSession();
        session.setWorkspaceIdentity(run.getWorkspaceIdentity());

        RemoteWorkerClient.SourceTreeFingerprint fingerprint = client.fingerprintSourceTree(session);

        JsonNode body = requestBody.get();
        assertEquals(run.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(run.getWorkspaceIdentity(), body.get("workspaceIdentity").asText());
        assertEquals(TEST_CANONICAL_COMMIT, body.get("commit").asText());
        assertNull(body.get("path"));
        assertNull(body.get("command"));
        assertNull(body.get("environment"));
        assertEquals("4".repeat(64), fingerprint.fingerprintSha256());
        assertEquals(false, fingerprint.valuesExposed());
    }

    @Test
    void closedValidationUsesOnlyFixedPersistedAuthority() {
        AgentRunEntity run = projectRun(null);
        WorkSessionEntity session = run.getSession();
        session.setWorkspaceIdentity(run.getWorkspaceIdentity());
        String validationId = "0cc7815a-f703-46ee-938a-8ef4d00e68a2";

        RemoteWorkerClient.ValidationResult result = client.runValidation(
                session,
                com.atenea.persistence.worksession.ValidationOperationKind.WEB_BUILD,
                "4".repeat(64),
                validationId);

        JsonNode body = requestBody.get();
        assertEquals(11, body.size());
        assertEquals(validationId, body.get("validationId").asText());
        assertEquals("WEB_BUILD", body.get("operation").asText());
        assertEquals("atenea-web-build-v1", body.get("definitionRevision").asText());
        assertEquals(session.getWorkspaceIdentity(), body.get("workspaceIdentity").asText());
        assertNull(body.get("command"));
        assertNull(body.get("image"));
        assertNull(body.get("compose"));
        assertNull(body.get("environment"));
        assertNull(body.get("path"));
        assertNull(body.get("host"));
        assertNull(body.get("slot"));
        assertNull(body.get("endpoint"));
        assertNull(body.get("credential"));
        assertEquals("SUCCEEDED", result.status());
        assertEquals(false, result.valuesExposed());
    }

    @Test
    void repositoryRolesUseOnlyPersistedSessionAndGeneratedChangeIdentity() {
        AgentRunEntity run = projectRun(null);
        WorkSessionEntity session = run.getSession();
        session.setWorkspaceIdentity(run.getWorkspaceIdentity());
        String change = "0cc7815a-f703-46ee-938a-8ef4d00e68a2";

        RemoteWorkerClient.RepositoryRoleSet result =
                client.ensureRepositoryRoles(session, change);

        JsonNode body = requestBody.get();
        assertEquals(4, body.size());
        assertEquals(change, body.get("changeIdentity").asText());
        assertEquals(TEST_CANONICAL_COMMIT, body.get("codeCommit").asText());
        assertNull(body.get("repository"));
        assertNull(body.get("branch"));
        assertNull(body.get("path"));
        assertNull(body.get("command"));
        assertNull(body.get("user"));
        assertEquals(3, result.roles().size());
        assertEquals(false, result.valuesExposed());
    }

    @Test
    void conflictingPersistedProjectFingerprintFailsBeforeNetwork() {
        AgentRunEntity run = projectRun(null);
        run.setRepositoryCommit("0".repeat(40));

        RemoteWorkerException exception = assertThrows(
                RemoteWorkerException.class,
                () -> client.dispatch(run, "Must fail."));

        assertEquals(409, exception.getStatusCode());
        assertNull(requestBody.get());
    }

    @Test
    void changedInstructionBundleFailsBeforeNetwork() {
        AgentRunEntity run = projectRun(null);
        run.setInstructionBundleSha256("f".repeat(64));

        RemoteWorkerException exception = assertThrows(
                RemoteWorkerException.class,
                () -> client.dispatch(run, "Must fail."));

        assertEquals(409, exception.getStatusCode());
        assertNull(requestBody.get());
    }

    @Test
    void syntheticDispatchRemainsCompatible() {
        AgentRunEntity run = projectRun(null);
        run.setWorkloadKind("synthetic-routing-v1");
        run.setProjectIdentity(null);
        run.setRepositoryUrl(null);
        run.setRepositoryBranch(null);
        run.setRepositoryCommit(null);
        run.setManifestSha256(null);

        client.dispatch(run, "Synthetic turn.");

        JsonNode workload = requestBody.get().get("workload");
        assertEquals("synthetic-routing-v1", workload.get("kind").asText());
        assertEquals(4, workload.size());
    }

    @Test
    void exactCancelReconcileAndDoctorDeriveOnlyPersistedOwnership() {
        AgentRunEntity run = projectRun(null);
        run.setRemoteExecutionId("4ee2d311-b9da-4307-89b6-dd3110ef2057");

        RemoteWorkerClient.Execution cancelled = client.cancelExact(run);
        assertExactOperationBody(run);
        RemoteWorkerClient.Execution reconciled = client.inspectReconciliation(run);
        assertExactOperationBody(run);
        RemoteWorkerClient.ExecutionDoctor diagnostic = client.doctor(run);
        assertExactOperationBody(run);

        assertEquals(run.getDispatchId().toString(), cancelled.dispatchId());
        assertEquals(run.getDispatchId().toString(), reconciled.dispatchId());
        assertEquals("agent-run-doctor-v1", diagnostic.schemaVersion());
        assertEquals("PERSISTED_NO_PROCESS", diagnostic.observation());
        assertEquals(false, diagnostic.valuesExposed());
    }

    @Test
    void exactOperationsFailBeforeNetworkWhenPersistedOwnershipIsIncomplete() {
        AgentRunEntity run = projectRun(null);

        java.util.List<java.util.function.Consumer<AgentRunEntity>> operations = java.util.List.of(
                value -> client.cancelExact(value),
                value -> client.inspectReconciliation(value),
                value -> client.doctor(value));
        for (java.util.function.Consumer<AgentRunEntity> operation : operations) {
            RemoteWorkerException exception = assertThrows(
                    RemoteWorkerException.class,
                    () -> operation.accept(run));
            assertEquals(409, exception.getStatusCode());
            assertNull(requestBody.get());
        }
    }

    private void assertExactOperationBody(AgentRunEntity run) {
        JsonNode body = requestBody.get();
        assertEquals(4, body.size());
        assertEquals(run.getRemoteExecutionId(), body.get("executionId").asText());
        assertEquals(run.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(run.getWorkspaceIdentity(), body.get("workspaceIdentity").asText());
        assertEquals(run.getLeaseGeneration(), body.get("leaseGeneration").asLong());
        for (String forbidden : java.util.List.of(
                "command", "host", "service", "path", "slot", "endpoint",
                "environment", "credential")) {
            assertNull(body.get(forbidden));
        }
    }

    private AgentRunEntity workspaceRun() {
        AgentRunEntity run = projectRun(null);
        run.getSession().setWorkspaceBranch("atenea/session-" + run.getRemoteSessionId());
        return run;
    }

    private java.util.Map<String, Object> operationResponse(
            String dispatchId,
            JsonNode request
    ) {
        Instant now = Instant.parse("2026-07-29T06:00:00Z");
        return java.util.Map.ofEntries(
                java.util.Map.entry("dispatchId", dispatchId),
                java.util.Map.entry("executionId", request.get("executionId").asText()),
                java.util.Map.entry("sessionId", request.get("sessionId").asText()),
                java.util.Map.entry("workspaceIdentity", request.get("workspaceIdentity").asText()),
                java.util.Map.entry("workloadClass", "NORMAL"),
                java.util.Map.entry("leaseGeneration", request.get("leaseGeneration").asLong()),
                java.util.Map.entry("status", "RUNNING"),
                java.util.Map.entry("statusReason", "Exact project Codex execution running"),
                java.util.Map.entry("revision", 4),
                java.util.Map.entry("progress", 10),
                java.util.Map.entry("createdAt", now),
                java.util.Map.entry("updatedAt", now),
                java.util.Map.entry("startedAt", now),
                java.util.Map.entry("progressEvents", java.util.List.of()));
    }

    private java.util.Map<String, Object> doctorResponse(
            String dispatchId,
            JsonNode request
    ) {
        return java.util.Map.ofEntries(
                java.util.Map.entry("schemaVersion", "agent-run-doctor-v1"),
                java.util.Map.entry("workerId", "ax42-01"),
                java.util.Map.entry("dispatchId", dispatchId),
                java.util.Map.entry("executionId", request.get("executionId").asText()),
                java.util.Map.entry("sessionId", request.get("sessionId").asText()),
                java.util.Map.entry("workspaceIdentity", request.get("workspaceIdentity").asText()),
                java.util.Map.entry("leaseGeneration", request.get("leaseGeneration").asLong()),
                java.util.Map.entry("status", "RUNNING"),
                java.util.Map.entry("revision", 4),
                java.util.Map.entry("observation", "PERSISTED_NO_PROCESS"),
                java.util.Map.entry("cancelRequested", false),
                java.util.Map.entry("reconcileRequired", false),
                java.util.Map.entry("retainedProgressCount", 2),
                java.util.Map.entry("valuesExposed", false));
    }

    private WorkSessionEntity releasableSession() {
        WorkSessionEntity session = projectRun(null).getSession();
        String sessionId = session.getRemoteSessionId().toString();
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        session.setWorkspaceIdentity(
                "remote:" + ProjectCodexIdentity.WORKER_ID + ":work-session:" + sessionId);
        session.setWorkspaceBranch("atenea/session-" + sessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setRemoteCloseState(RemoteCloseState.REQUESTED);
        session.setRemoteCloseOperationId(
                UUID.fromString("22222222-2222-4222-8222-222222222222"));
        session.setRemoteCloseRevision(1);
        session.setRemoteCloseRequestedAt(Instant.parse("2026-08-03T09:00:00Z"));
        session.setRemoteCloseUpdatedAt(Instant.parse("2026-08-03T09:00:00Z"));
        return session;
    }

    private void clearCanonicalSourceObservation(WorkSessionEntity session) {
        session.setCanonicalSourceRef(null);
        session.setCanonicalSourceCommit(null);
        session.setCanonicalSourceObservationSha256(null);
        session.setCanonicalSourceObservedAt(null);
    }

    private Map<String, Object> workspaceReleaseReceipt(JsonNode request) {
        Map<String, Object> receipt = new java.util.LinkedHashMap<>();
        receipt.put("schemaVersion", "project-workspace-release-v1");
        receipt.put("state", "RELEASED");
        request.fields().forEachRemaining(entry -> receipt.put(entry.getKey(), entry.getValue().asText()));
        receipt.put("workerId", ProjectCodexIdentity.WORKER_ID);
        receipt.put("requestFingerprintSha256", canonicalSha256(request));
        receipt.put("revision", 6);
        receipt.put("removed", Map.of(
                "runtimeContainers", 0,
                "runtimeNetworks", 0,
                "sessionImages", 0,
                "previewResources", 0,
                "brokerResources", 0,
                "browserProcesses", 0));
        receipt.put("released", Map.of(
                "registration", true,
                "normalAdmission", true,
                "heavyAdmission", true,
                "allocation", true));
        receipt.put("retained", Map.of(
                "workspaceRecord", true,
                "worktree", true,
                "git", true,
                "turns", true,
                "agentRuns", true,
                "attachments", true,
                "logs", true,
                "artifacts", true,
                "backups", true,
                "policyVolumes", true));
        receipt.put("ownershipFingerprintSha256", "4".repeat(64));
        receipt.put("valuesExposed", false);
        sealWorkspaceReleaseReceipt(receipt);
        return receipt;
    }

    private void sealWorkspaceReleaseReceipt(Map<String, Object> receipt) {
        receipt.remove("receiptSha256");
        receipt.put("receiptSha256", canonicalSha256(objectMapper.valueToTree(receipt)));
    }

    private String canonicalSha256(JsonNode value) {
        return sha256(canonicalize(value));
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            Set<String> fields = new java.util.TreeSet<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> result.set(field, canonicalize(value.get(field))));
            return result;
        }
        if (value.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode result = objectMapper.createArrayNode();
            value.forEach(child -> result.add(canonicalize(child)));
            return result;
        }
        return value;
    }

    private AgentRunEntity projectRun(String threadId) {
        UUID remoteSessionId = UUID.fromString("4bb26a65-0a0a-4ae0-b8e0-b41e03a695bf");
        ProjectEntity project = new ProjectEntity();
        project.setId(1L);
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setProject(project);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExternalThreadId(threadId);
        session.setRemoteSessionId(remoteSessionId);
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit(TEST_CANONICAL_COMMIT);
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.now());
        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session);
        run.setDispatchId(UUID.fromString("3bb4ab61-6439-452d-a1cc-90e2eb9d9310"));
        run.setRemoteSessionId(remoteSessionId);
        run.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteSessionId);
        run.setWorkloadClass(WorkloadClass.NORMAL);
        run.setLeaseGeneration(1);
        run.setStatus(AgentRunStatus.QUEUED);
        run.setWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        run.setProjectIdentity(ProjectCodexIdentity.PROJECT_IDENTITY);
        run.setRepositoryUrl(ProjectCodexIdentity.REPOSITORY);
        run.setRepositoryBranch(ProjectCodexIdentity.BRANCH);
        run.setRepositoryCommit(TEST_CANONICAL_COMMIT);
        run.setManifestSha256(ProjectCodexIdentity.MANIFEST_SHA256);
        ReviewedInstructionBundleIdentity.apply(
                run, ProjectCodexIdentity.PROJECT_IDENTITY);
        return run;
    }

    private AgentRunEntity profiledImageRun(String threadId) {
        AgentRunEntity run = projectRun(threadId);
        run.setSelectedWorkerId("ax42-01");
        run.setWorkloadKind(ProjectCodexIdentity.IMAGE_WORKLOAD_KIND);
        applyProfile(run);
        run.setAttachmentCount(1);
        run.setAttachmentBytes(1024L);
        run.setAttachmentManifestSha256("c".repeat(64));
        return run;
    }

    private void applyProfile(AgentRunEntity run) {
        run.setCodexModelId("gpt-5.6-sol");
        run.setCodexReasoningEffort(
                com.atenea.persistence.worksession.CodexReasoningEffort.HIGH);
        run.setCodexCatalogRevision(
                "125b9437e38f83e04cb10996fc70d3ab44c32082009b8e897cb08bb340b13187");
        run.setCodexVersion("0.145.0");
    }

    private String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(value)));
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private java.util.Map<String, Object> role(
            String role, String branch, String commit, String profile
    ) {
        return java.util.Map.of(
                "role", role,
                "authority", "READ_WRITE",
                "repository", ProjectCodexIdentity.REPOSITORY,
                "branch", branch,
                "commit", commit,
                "mirrorIdentitySha256", "6".repeat(64),
                "worktreeIdentitySha256", Integer.toHexString(role.hashCode())
                        .replace("-", "a").repeat(64).substring(0, 64),
                "validationProfile", profile,
                "readiness", "DRAFT");
    }

    private AgentRunEntity beautipsRun(String threadId) {
        AgentRunEntity run = projectRun(threadId);
        ProjectEntity project = run.getSession().getProject();
        project.setName(BeautipsProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(BeautipsProjectCodexIdentity.REPO_PATH);
        project.setDefaultBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        run.getSession().setBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        run.setSelectedWorkerId(BeautipsProjectCodexIdentity.WORKER_ID);
        run.setProjectIdentity(BeautipsProjectCodexIdentity.PROJECT_IDENTITY);
        run.setRepositoryUrl(BeautipsProjectCodexIdentity.REPOSITORY);
        run.setRepositoryBranch(BeautipsProjectCodexIdentity.BRANCH);
        run.setRepositoryCommit(BeautipsProjectCodexIdentity.COMMIT);
        run.setManifestSha256(BeautipsProjectCodexIdentity.MANIFEST_SHA256);
        ReviewedInstructionBundleIdentity.apply(
                run, BeautipsProjectCodexIdentity.PROJECT_IDENTITY);
        return run;
    }
}
