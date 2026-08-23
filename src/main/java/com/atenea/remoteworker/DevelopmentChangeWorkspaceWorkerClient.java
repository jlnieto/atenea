package com.atenea.remoteworker;

import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DevelopmentChangeWorkspaceWorkerClient
        implements DevelopmentChangeWorkspaceGateway {

    private static final String PATH_PREFIX = "/v1/development-changes/workspaces/";
    private static final String PROTOCOL_FAILURE =
            "DEVELOPMENT_CHANGE_WORKER_PROTOCOL_FAILURE";
    private static final String OWNERSHIP_FAILURE =
            "DEVELOPMENT_CHANGE_WORKER_OWNERSHIP_MISMATCH";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern GIT_COMMIT = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");

    private final RemoteWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DevelopmentChangeWorkspaceWorkerClient(
            RemoteWorkerProperties properties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    @Override
    public DevelopmentChangeWorkspaceObservation execute(
            DevelopmentChangeWorkspaceCommand command) {
        Objects.requireNonNull(command, "command");
        Map<String, Object> body = requestBody(command);
        String requestFingerprint = canonicalSha256(objectMapper.valueToTree(body));
        body.put("requestFingerprintSha256", requestFingerprint);
        RawObservation response = exchange(
                PATH_PREFIX + command.operationKind().name().toLowerCase(),
                body,
                command.idempotencyKey().toString());
        validateIdentity(command, requestFingerprint, response);
        DevelopmentChangeWorkspaceObservation.Disposition disposition;
        try {
            disposition = DevelopmentChangeWorkspaceObservation.Disposition.valueOf(
                    response.state());
        } catch (RuntimeException invalid) {
            throw protocolFailure();
        }
        if (!SHA256.matcher(Objects.toString(response.ownershipFingerprintSha256(), ""))
                .matches()
                || !Boolean.FALSE.equals(response.valuesExposed())) {
            throw protocolFailure();
        }
        if (disposition == DevelopmentChangeWorkspaceObservation.Disposition.OWNED) {
            if (!GIT_COMMIT.matcher(Objects.toString(response.canonicalCommit(), "")).matches()
                    || !SHA256.matcher(Objects.toString(
                            response.sourceFingerprintSha256(), "")).matches()
                    || response.workspaceDirty() == null
                    || response.retainedDraft() == null) {
                throw protocolFailure();
            }
        } else if (response.canonicalCommit() != null
                || response.sourceFingerprintSha256() != null
                || response.workspaceDirty() != null
                || response.retainedDraft() != null) {
            throw protocolFailure();
        }
        return new DevelopmentChangeWorkspaceObservation(
                disposition,
                response.canonicalCommit(),
                response.sourceFingerprintSha256(),
                Boolean.TRUE.equals(response.workspaceDirty()),
                Boolean.TRUE.equals(response.retainedDraft()),
                response.requestFingerprintSha256(),
                response.ownershipFingerprintSha256());
    }

    private Map<String, Object> requestBody(DevelopmentChangeWorkspaceCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", DevelopmentChangeWorkspaceCommand.SCHEMA_VERSION);
        body.put("protocolVersion", DevelopmentChangeWorkspaceCommand.PROTOCOL_VERSION);
        body.put("effect", command.effect());
        body.put("operationId", command.operationId().toString());
        body.put("idempotencyKey", command.idempotencyKey().toString());
        body.put("operation", command.operationKind().name());
        body.put("predecessorOperationId", command.predecessorOperationId() == null
                ? null : command.predecessorOperationId().toString());
        body.put("changeKey", command.changeKey().toString());
        body.put("databaseProjectId", command.databaseProjectId());
        body.put("projectId", command.projectIdentity());
        body.put("repository", command.repository());
        body.put("repositoryBranch", command.repositoryBranch());
        body.put("baseCommit", command.baseCommit());
        body.put("expectedCanonicalCommit", command.expectedCanonicalCommit());
        body.put("workspaceBranch", command.workspaceBranch());
        body.put("workspaceIdentity", command.workspaceIdentity());
        body.put("workerId", command.workerId());
        body.put("sourceRevision", command.sourceRevision());
        body.put("sourceFingerprintSha256", command.sourceFingerprintSha256());
        return body;
    }

    private void validateIdentity(
            DevelopmentChangeWorkspaceCommand command,
            String requestFingerprint,
            RawObservation response) {
        if (!Integer.valueOf(DevelopmentChangeWorkspaceCommand.SCHEMA_VERSION)
                    .equals(response.schemaVersion())
                || !DevelopmentChangeWorkspaceCommand.PROTOCOL_VERSION.equals(
                        response.protocolVersion())
                || !command.effect().equals(response.effect())
                || !command.operationId().toString().equals(response.operationId())
                || !command.idempotencyKey().toString().equals(response.idempotencyKey())
                || !command.operationKind().name().equals(response.operation())
                || !Objects.equals(
                        command.predecessorOperationId() == null
                                ? null : command.predecessorOperationId().toString(),
                        response.predecessorOperationId())
                || !command.changeKey().toString().equals(response.changeKey())
                || !Long.valueOf(command.databaseProjectId()).equals(response.databaseProjectId())
                || !command.projectIdentity().equals(response.projectId())
                || !command.repository().equals(response.repository())
                || !command.repositoryBranch().equals(response.repositoryBranch())
                || !command.baseCommit().equals(response.baseCommit())
                || !command.expectedCanonicalCommit().equals(response.expectedCanonicalCommit())
                || !command.workspaceBranch().equals(response.workspaceBranch())
                || !command.workspaceIdentity().equals(response.workspaceIdentity())
                || !command.workerId().equals(response.workerId())
                || !Long.valueOf(command.sourceRevision()).equals(response.sourceRevision())
                || !command.sourceFingerprintSha256().equals(
                        response.expectedSourceFingerprintSha256())
                || !requestFingerprint.equals(response.requestFingerprintSha256())) {
            throw ownershipFailure();
        }
    }

    private RawObservation exchange(
            String path,
            Map<String, Object> body,
            String idempotencyKey) {
        HttpResponse<InputStream> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(properties.getEndpoint()) + path))
                    .timeout(properties.getWorkspaceProvisionTimeout())
                    .header("Authorization", "Bearer " + readToken())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            objectMapper.writeValueAsBytes(body)))
                    .build();
            response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException failure) {
            throw new RemoteWorkerException("Development change worker I/O failed", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RemoteWorkerException(
                    "Development change worker request was interrupted", failure);
        } catch (RuntimeException failure) {
            throw new RemoteWorkerException(
                    "Development change worker response was invalid",
                    502,
                    PROTOCOL_FAILURE,
                    RemoteWorkerFailureCategory.PROTOCOL,
                    false,
                    AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                    null);
        }
        try (InputStream responseBody = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw rejected(response.statusCode());
            }
            try {
                return objectMapper.readValue(responseBody, RawObservation.class);
            } catch (IOException malformed) {
                throw protocolFailure();
            }
        } catch (RemoteWorkerException failure) {
            throw failure;
        } catch (IOException closeFailure) {
            throw new RemoteWorkerException(
                    "Development change worker response close failed", closeFailure);
        }
    }

    private RemoteWorkerException rejected(int status) {
        if (status >= 500 && status < 600) {
            return new RemoteWorkerException(
                    "Development change worker response is uncertain",
                    status,
                    "DEVELOPMENT_CHANGE_WORKER_RESPONSE_UNCERTAIN",
                    RemoteWorkerFailureCategory.TRANSPORT,
                    true,
                    AgentRunRecoveryNextAction.REQUEST_RECONCILIATION,
                    null);
        }
        if (status == 429) {
            return new RemoteWorkerException(
                    "Development change worker capacity is unavailable",
                    status,
                    "DEVELOPMENT_CHANGE_WORKER_CAPACITY_WAIT",
                    RemoteWorkerFailureCategory.CAPACITY,
                    true,
                    AgentRunRecoveryNextAction.WAIT,
                    null);
        }
        return new RemoteWorkerException(
                "Development change worker rejected the exact request",
                status,
                status == 409 ? OWNERSHIP_FAILURE : PROTOCOL_FAILURE,
                status == 409
                        ? RemoteWorkerFailureCategory.OWNERSHIP
                        : RemoteWorkerFailureCategory.PROTOCOL,
                false,
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                null);
    }

    private RemoteWorkerException ownershipFailure() {
        return new RemoteWorkerException(
                "Development change worker identity did not match",
                409,
                OWNERSHIP_FAILURE,
                RemoteWorkerFailureCategory.OWNERSHIP,
                false,
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                null);
    }

    private RemoteWorkerException protocolFailure() {
        return new RemoteWorkerException(
                "Development change worker response violated the fixed contract",
                502,
                PROTOCOL_FAILURE,
                RemoteWorkerFailureCategory.PROTOCOL,
                false,
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                null);
    }

    private String readToken() throws IOException {
        if (properties.getTokenFile() == null || properties.getTokenFile().isBlank()) {
            throw new IOException("remote worker token file is not configured");
        }
        String token = Files.readString(Path.of(properties.getTokenFile())).trim();
        if (token.length() < 32) {
            throw new IOException("remote worker token file is invalid");
        }
        return token;
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String canonicalSha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonicalize(value))));
        } catch (NoSuchAlgorithmException | IOException failure) {
            throw new IllegalStateException("SHA-256 canonicalization is unavailable", failure);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            fields.forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, child) -> result.set(key, canonicalize(child)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(child -> result.add(canonicalize(child)));
            return result;
        }
        return value;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    private record RawObservation(
            Integer schemaVersion,
            String protocolVersion,
            String state,
            String effect,
            String operationId,
            String idempotencyKey,
            String operation,
            String predecessorOperationId,
            String changeKey,
            Long databaseProjectId,
            String projectId,
            String repository,
            String repositoryBranch,
            String baseCommit,
            String expectedCanonicalCommit,
            String workspaceBranch,
            String workspaceIdentity,
            String workerId,
            Long sourceRevision,
            String expectedSourceFingerprintSha256,
            String canonicalCommit,
            String sourceFingerprintSha256,
            Boolean workspaceDirty,
            Boolean retainedDraft,
            String requestFingerprintSha256,
            String ownershipFingerprintSha256,
            Boolean valuesExposed) {
    }
}
