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
public class DevelopmentChangeBranchPublicationWorkerClient
        implements DevelopmentChangeBranchPublicationGateway {

    private static final String PATH = "/v1/development-changes/branches/publish";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern GIT_COMMIT = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");

    private final RemoteWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DevelopmentChangeBranchPublicationWorkerClient(
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
    public DevelopmentChangeBranchPublication publish(
            DevelopmentChangeBranchPublicationCommand command) {
        Objects.requireNonNull(command, "command");
        Map<String, Object> body = requestBody(command);
        String requestFingerprint = canonicalSha256(objectMapper.valueToTree(body));
        body.put("requestFingerprintSha256", requestFingerprint);
        RawPublication response = exchange(body, command.idempotencyKey().toString());
        validateExact(command, requestFingerprint, response);
        DevelopmentChangeBranchPublication.RemoteDisposition disposition;
        try {
            disposition = DevelopmentChangeBranchPublication.RemoteDisposition.valueOf(
                    response.remoteDisposition());
        } catch (RuntimeException invalid) {
            throw protocolFailure();
        }
        return new DevelopmentChangeBranchPublication(
                response.publishedHeadSha(),
                disposition,
                response.requestFingerprintSha256(),
                response.publicationReceiptSha256());
    }

    private Map<String, Object> requestBody(
            DevelopmentChangeBranchPublicationCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", DevelopmentChangeBranchPublicationCommand.SCHEMA_VERSION);
        body.put("protocolVersion", DevelopmentChangeBranchPublicationCommand.PROTOCOL_VERSION);
        body.put("effect", DevelopmentChangeBranchPublicationCommand.EFFECT);
        body.put("operationId", command.operationId().toString());
        body.put("idempotencyKey", command.idempotencyKey().toString());
        body.put("operation", DevelopmentChangeBranchPublicationCommand.OPERATION);
        body.put("changeKey", command.changeKey().toString());
        body.put("databaseProjectId", command.databaseProjectId());
        body.put("projectId", command.projectIdentity());
        body.put("repository", command.repository());
        body.put("repositoryBranch", command.repositoryBranch());
        body.put("baseCommit", command.baseCommit());
        body.put("sourceCommit", command.sourceCommit());
        body.put("workspaceBranch", command.workspaceBranch());
        body.put("workspaceIdentity", command.workspaceIdentity());
        body.put("workerId", command.workerId());
        body.put("sourceRevision", command.sourceRevision());
        body.put("sourceFingerprintSha256", command.sourceFingerprintSha256());
        return body;
    }

    private void validateExact(
            DevelopmentChangeBranchPublicationCommand command,
            String requestFingerprint,
            RawPublication response) {
        if (!Integer.valueOf(DevelopmentChangeBranchPublicationCommand.SCHEMA_VERSION)
                    .equals(response.schemaVersion())
                || !DevelopmentChangeBranchPublicationCommand.PROTOCOL_VERSION.equals(
                        response.protocolVersion())
                || !"PUBLISHED".equals(response.state())
                || !DevelopmentChangeBranchPublicationCommand.EFFECT.equals(response.effect())
                || !command.operationId().toString().equals(response.operationId())
                || !command.idempotencyKey().toString().equals(response.idempotencyKey())
                || !DevelopmentChangeBranchPublicationCommand.OPERATION.equals(response.operation())
                || !command.changeKey().toString().equals(response.changeKey())
                || !Long.valueOf(command.databaseProjectId()).equals(response.databaseProjectId())
                || !command.projectIdentity().equals(response.projectId())
                || !command.repositoryBranch().equals(response.repositoryBranch())
                || !command.baseCommit().equals(response.baseCommit())
                || !command.sourceCommit().equals(response.sourceCommit())
                || !command.workspaceBranch().equals(response.workspaceBranch())
                || !command.workspaceIdentity().equals(response.workspaceIdentity())
                || !command.workerId().equals(response.workerId())
                || !Long.valueOf(command.sourceRevision()).equals(response.sourceRevision())
                || !Objects.equals(command.sourceFingerprintSha256(),
                        response.sourceFingerprintSha256())
                || !requestFingerprint.equals(response.requestFingerprintSha256())
                || !GIT_COMMIT.matcher(Objects.toString(response.publishedHeadSha(), "")).matches()
                || !SHA256.matcher(Objects.toString(
                        response.publicationReceiptSha256(), "")).matches()
                || !Boolean.FALSE.equals(response.valuesExposed())) {
            throw protocolFailure();
        }
    }

    private RawPublication exchange(Map<String, Object> body, String idempotencyKey) {
        HttpResponse<InputStream> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(properties.getEndpoint()) + PATH))
                    .timeout(properties.getWorkspaceProvisionTimeout())
                    .header("Authorization", "Bearer " + readToken())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            objectMapper.writeValueAsBytes(body)))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException failure) {
            throw new RemoteWorkerException("Development change publication I/O failed", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RemoteWorkerException(
                    "Development change publication was interrupted", failure);
        } catch (RuntimeException failure) {
            throw protocolFailure();
        }
        try (InputStream responseBody = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw rejected(response.statusCode());
            }
            try {
                return objectMapper.readValue(responseBody, RawPublication.class);
            } catch (IOException malformed) {
                throw protocolFailure();
            }
        } catch (RemoteWorkerException failure) {
            throw failure;
        } catch (IOException closeFailure) {
            throw new RemoteWorkerException(
                    "Development change publication response close failed", closeFailure);
        }
    }

    private RemoteWorkerException rejected(int status) {
        boolean uncertain = status >= 500;
        return new RemoteWorkerException(
                uncertain
                        ? "Development change publication response is uncertain"
                        : "Development change publication was rejected",
                status,
                uncertain
                        ? "DEVELOPMENT_CHANGE_PUBLICATION_RESPONSE_UNCERTAIN"
                        : "DEVELOPMENT_CHANGE_PUBLICATION_REJECTED",
                uncertain
                        ? RemoteWorkerFailureCategory.TRANSPORT
                        : RemoteWorkerFailureCategory.OWNERSHIP,
                uncertain,
                uncertain
                        ? AgentRunRecoveryNextAction.REQUEST_RECONCILIATION
                        : AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR,
                null);
    }

    private RemoteWorkerException protocolFailure() {
        return new RemoteWorkerException(
                "Development change publication response violated the fixed contract",
                502,
                "DEVELOPMENT_CHANGE_PUBLICATION_PROTOCOL_FAILURE",
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
    private record RawPublication(
            Integer schemaVersion,
            String protocolVersion,
            String state,
            String effect,
            String operationId,
            String idempotencyKey,
            String operation,
            String changeKey,
            Long databaseProjectId,
            String projectId,
            String repositoryBranch,
            String baseCommit,
            String sourceCommit,
            String workspaceBranch,
            String workspaceIdentity,
            String workerId,
            Long sourceRevision,
            String sourceFingerprintSha256,
            String publishedHeadSha,
            String remoteDisposition,
            String requestFingerprintSha256,
            String publicationReceiptSha256,
            Boolean valuesExposed) {
    }
}
