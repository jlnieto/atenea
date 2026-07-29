package com.atenea.remoteworker;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RemoteWorkerClient {

    private final RemoteWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RemoteWorkerClient(RemoteWorkerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    public Health health() {
        return exchange("GET", "/v1/health", null, Health.class);
    }

    public Execution dispatch(AgentRunEntity run, String message) {
        Map<String, Object> workload = workload(run, message);
        Map<String, Object> body = Map.of(
                "dispatchId", run.getDispatchId().toString(),
                "sessionId", run.getRemoteSessionId().toString(),
                "workspaceIdentity", run.getWorkspaceIdentity(),
                "workloadClass", run.getWorkloadClass().name(),
                "leaseGeneration", run.getLeaseGeneration(),
                "workload", workload);
        return exchange(
                "POST",
                "/v1/executions",
                body,
                Execution.class,
                run.getDispatchId().toString());
    }

    private Map<String, Object> workload(AgentRunEntity run, String message) {
        if ("synthetic-routing-v1".equals(run.getWorkloadKind())) {
            return Map.of(
                    "kind", "synthetic-routing-v1",
                    "message", message,
                    "durationMs", properties.getSyntheticDuration().toMillis(),
                    "steps", 10);
        }
        if (!ProjectCodexIdentity.matches(run)) {
            throw new RemoteWorkerException(
                    "Persisted project workload identity is incomplete or incompatible",
                    409);
        }
        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("kind", ProjectCodexIdentity.WORKLOAD_KIND);
        workload.put("projectId", run.getProjectIdentity());
        workload.put("repository", run.getRepositoryUrl());
        workload.put("branch", run.getRepositoryBranch());
        workload.put("commit", run.getRepositoryCommit());
        workload.put("manifestSha256", run.getManifestSha256());
        workload.put("message", message);
        workload.put("threadId", run.getSession().getExternalThreadId());
        return workload;
    }

    public Execution get(AgentRunEntity run) {
        return exchange("GET", "/v1/executions/" + run.getDispatchId(), null, Execution.class);
    }

    public Execution renew(AgentRunEntity run) {
        Map<String, Object> body = Map.of(
                "executionId", run.getRemoteExecutionId(),
                "leaseGeneration", run.getLeaseGeneration());
        return exchange("POST", "/v1/executions/" + run.getDispatchId() + "/lease", body, Execution.class);
    }

    public Execution cancel(AgentRunEntity run) {
        Map<String, Object> body = Map.of("executionId", run.getRemoteExecutionId());
        return exchange("POST", "/v1/executions/" + run.getDispatchId() + "/cancel", body, Execution.class);
    }

    private <T> T exchange(String method, String path, Object body, Class<T> responseType) {
        return exchange(method, path, body, responseType, null);
    }

    private <T> T exchange(
            String method,
            String path,
            Object body,
            Class<T> responseType,
            String idempotencyKey
    ) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(properties.getEndpoint()) + path))
                    .timeout(properties.getRequestTimeout())
                    .header("Authorization", "Bearer " + readToken())
                    .header("Accept", "application/json");
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)));
            }
            HttpResponse<byte[]> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RemoteWorkerException(
                        "Remote worker rejected request with HTTP " + response.statusCode(),
                        response.statusCode());
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (RemoteWorkerException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new RemoteWorkerException("Remote worker I/O failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RemoteWorkerException("Remote worker request was interrupted", exception);
        } catch (RuntimeException exception) {
            throw new RemoteWorkerException("Remote worker request failed", exception);
        }
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

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Health(
            String protocolVersion,
            String workerId,
            boolean healthy,
            List<String> capabilities,
            int normalCapacity,
            int heavyCapacity,
            int normalInUse,
            int heavyInUse,
            int queued,
            Instant serverTime
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Execution(
            String dispatchId,
            String executionId,
            String sessionId,
            String workspaceIdentity,
            String workloadClass,
            long leaseGeneration,
            String status,
            String statusReason,
            long revision,
            int progress,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant finishedAt,
            Result result
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Result(String threadId, String turnId, String finalAnswer, String outputSummary) {
    }
}
