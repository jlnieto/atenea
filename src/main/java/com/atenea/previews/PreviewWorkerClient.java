package com.atenea.previews;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PreviewWorkerClient {

    private final PreviewProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PreviewWorkerClient(PreviewProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Health health() {
        return exchange("GET", "/v1/health", null, Health.class);
    }

    public Projection activate(Ownership ownership, UUID runtimeSessionId) {
        Map<String, Object> body = operationBody(ownership);
        body.put("runtimeSessionId", runtimeSessionId.toString());
        return exchange("POST", path(ownership.previewId(), "activate"), body, Projection.class);
    }

    public Projection inspect(Ownership ownership) {
        String query = "?workSessionId=" + encode(ownership.workSessionId())
                + "&projectId=" + encode(ownership.projectId())
                + "&workerId=" + encode(ownership.workerId())
                + "&allocationIdentity=" + encode(ownership.allocationIdentity())
                + "&allocationFingerprint=" + encode(ownership.allocationFingerprint());
        return exchange("GET", path(ownership.previewId(), "") + query, null, Projection.class);
    }

    public Projection renew(Ownership ownership) {
        return exchange("POST", path(ownership.previewId(), "renew"),
                operationBody(ownership), Projection.class);
    }

    public Projection stop(Ownership ownership) {
        return exchange("POST", path(ownership.previewId(), "stop"),
                operationBody(ownership), Projection.class);
    }

    public DeleteResult deleteSynthetic(Ownership ownership) {
        return exchange("DELETE", path(ownership.previewId(), "fixture"),
                operationBody(ownership), DeleteResult.class);
    }

    private Map<String, Object> operationBody(Ownership ownership) {
        return new java.util.LinkedHashMap<>(Map.of(
                "protocolVersion", PreviewProperties.PROTOCOL,
                "workSessionId", ownership.workSessionId(),
                "projectId", ownership.projectId(),
                "workerId", ownership.workerId(),
                "allocationIdentity", ownership.allocationIdentity(),
                "allocationFingerprint", ownership.allocationFingerprint(),
                "expectedRevision", ownership.expectedRevision(),
                "syntheticFixture", true));
    }

    private <T> T exchange(String method, String path, Object body, Class<T> responseType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(properties.getEndpoint()) + path))
                    .timeout(properties.getRequestTimeout())
                    .header("Authorization", "Bearer " + readToken())
                    .header("Accept", "application/json");
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(
                                objectMapper.writeValueAsBytes(body)));
            }
            HttpResponse<byte[]> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw rejection(response);
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (PreviewWorkerException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new PreviewWorkerException(
                    "El coordinador de previews no está disponible. Conservamos el estado para reconciliar.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PreviewWorkerException(
                    "La operación de preview fue interrumpida; no se confirmó ningún cambio.",
                    exception);
        } catch (RuntimeException exception) {
            throw new PreviewWorkerException(
                    "El coordinador de previews devolvió una respuesta no compatible.",
                    exception);
        }
    }

    private PreviewWorkerException rejection(HttpResponse<byte[]> response) {
        String code = "preview_worker_rejected";
        try {
            JsonNode parsed = objectMapper.readTree(response.body());
            if (parsed.hasNonNull("error")) {
                code = parsed.get("error").asText(code);
            }
        } catch (IOException ignored) {
            // Stable sanitized fallback.
        }
        return new PreviewWorkerException(actionable(code), response.statusCode(), code);
    }

    private String actionable(String code) {
        return switch (code) {
            case "unauthorized" -> "Atenea no puede autenticarse ante el coordinador de previews.";
            case "preview_not_found" -> "La proyección privada ya no existe en AX42.";
            case "stale_revision" -> "El preview cambió; actualiza su estado antes de reintentar.";
            case "preview_upstream_unavailable" ->
                    "El runtime todavía no escucha en su puerto privado declarado.";
            case "preview_capacity_exhausted" ->
                    "No hay un puerto privado libre; detén otro preview y reintenta.";
            case "allocation_ownership_conflict", "allocation_fingerprint_conflict",
                    "preview_ownership_conflict", "worker_ownership_conflict",
                    "ingress_listener_conflict", "listener_ownership_conflict" ->
                    "El ownership persistido del preview no coincide; no se ha modificado la ruta.";
            default -> "AX42 rechazó el preview de forma segura (" + code + ").";
        };
    }

    private String readToken() throws IOException {
        if (properties.getTokenFile() == null || properties.getTokenFile().isBlank()) {
            throw new IOException("preview token file is not configured");
        }
        String token = Files.readString(Path.of(properties.getTokenFile())).trim();
        if (token.length() < 32) {
            throw new IOException("preview token file is invalid");
        }
        return token;
    }

    private String path(UUID previewId, String operation) {
        return "/v1/previews/" + previewId + (operation.isBlank() ? "" : "/" + operation);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record Ownership(
            UUID previewId,
            String workSessionId,
            String projectId,
            String workerId,
            String allocationIdentity,
            String allocationFingerprint,
            long expectedRevision
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Health(
            String protocolVersion,
            String workerId,
            boolean healthy,
            String bind,
            List<Integer> ingressRange,
            int activePreviews,
            boolean publicSharing,
            boolean arbitraryUpstream,
            Instant serverTime
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Projection(
            String protocolVersion,
            UUID previewId,
            String workSessionId,
            String projectId,
            String workerId,
            String allocationIdentity,
            String allocationFingerprint,
            long lifecycleRevision,
            String state,
            String privateUrl,
            Instant leaseExpiresAt,
            Instant hardExpiresAt,
            boolean localhostCompatible,
            Tunnel tunnel,
            boolean syntheticFixture
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Tunnel(
            String sshDestination,
            String remoteHost,
            int remotePort,
            String path,
            boolean credentialIncluded,
            boolean runtimePortExposed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DeleteResult(
            String protocolVersion,
            UUID previewId,
            String workSessionId,
            boolean deleted
    ) {
    }
}
