package com.atenea.attachments;

import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentRetentionClass;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.AttachmentStorageScope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AttachmentWorkerClient {

    public static final String REAL_PROJECT_PROTOCOL = "real-project-attachment/v1";

    private final AttachmentProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AttachmentWorkerClient(AttachmentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Health health() {
        return json("GET", "/v1/health", null, Health.class);
    }

    public RealProjectCapability realProjectCapability() {
        return json(
                "GET",
                "/v1/capabilities/real-project-attachment",
                null,
                RealProjectCapability.class);
    }

    public PutResult putSynthetic(
            Long workSessionId,
            UUID attachmentId,
            AttachmentSource source,
            AttachmentKind kind,
            AttachmentRetentionClass retentionClass,
            String contentType,
            String sha256,
            Instant createdAt,
            Path content
    ) {
        return put(
                workSessionId.toString(),
                attachmentId,
                source,
                kind,
                retentionClass,
                contentType,
                sha256,
                createdAt,
                content,
                true,
                null,
                null,
                null);
    }

    public PutResult putReal(
            UUID remoteSessionId,
            String projectIdentity,
            String workspaceIdentity,
            AttachmentStorageScope storageScope,
            UUID attachmentId,
            AttachmentSource source,
            AttachmentKind kind,
            AttachmentRetentionClass retentionClass,
            String contentType,
            String sha256,
            Instant createdAt,
            Path content
    ) {
        return put(
                remoteSessionId.toString(),
                attachmentId,
                source,
                kind,
                retentionClass,
                contentType,
                sha256,
                createdAt,
                content,
                false,
                projectIdentity,
                workspaceIdentity,
                storageScope);
    }

    private PutResult put(
            String sessionIdentity,
            UUID attachmentId,
            AttachmentSource source,
            AttachmentKind kind,
            AttachmentRetentionClass retentionClass,
            String contentType,
            String sha256,
            Instant createdAt,
            Path content,
            boolean syntheticFixture,
            String projectIdentity,
            String workspaceIdentity,
            AttachmentStorageScope storageScope
    ) {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = request(path(sessionIdentity, attachmentId, "content"))
                    .header("Content-Type", contentType)
                    .header("X-Atenea-Source", source.name())
                    .header("X-Atenea-Kind", kind.name())
                    .header("X-Atenea-Retention-Class", retentionClass.name())
                    .header("X-Atenea-Sha256", sha256)
                    .header("X-Atenea-Synthetic-Fixture", Boolean.toString(syntheticFixture))
                    .header("X-Atenea-Created-At", createdAt.toString());
            if (!syntheticFixture) {
                builder.header("X-Atenea-Project-Identity", projectIdentity)
                        .header("X-Atenea-Workspace-Identity", workspaceIdentity)
                        .header("X-Atenea-Storage-Scope", storageScope.name());
            }
            request = builder.PUT(HttpRequest.BodyPublishers.ofFile(content)).build();
        } catch (IOException exception) {
            throw new AttachmentWorkerException(
                    "No se pudo abrir el spool privado del adjunto.",
                    exception);
        }
        HttpResponse<byte[]> response = send(request);
        requireSuccess(response);
        return new PutResult(
                response.statusCode() == 201,
                read(response.body(), StoredAttachment.class));
    }

    public StoredAttachment metadata(Long workSessionId, UUID attachmentId) {
        return metadata(workSessionId.toString(), attachmentId);
    }

    public StoredAttachment metadata(UUID remoteSessionId, UUID attachmentId) {
        return metadata(remoteSessionId.toString(), attachmentId);
    }

    private StoredAttachment metadata(String sessionIdentity, UUID attachmentId) {
        return json(
                "GET",
                path(sessionIdentity, attachmentId, "metadata"),
                null,
                StoredAttachment.class);
    }

    public Content content(Long workSessionId, UUID attachmentId) {
        return content(workSessionId.toString(), attachmentId);
    }

    public Content content(UUID remoteSessionId, UUID attachmentId) {
        return content(remoteSessionId.toString(), attachmentId);
    }

    private Content content(String sessionIdentity, UUID attachmentId) {
        HttpResponse<byte[]> response = send(request(path(sessionIdentity, attachmentId, "content"))
                .GET()
                .build());
        requireSuccess(response);
        return new Content(
                response.headers().firstValue("Content-Type").orElse("application/octet-stream"),
                response.body());
    }

    public DeleteResult deleteSynthetic(Long workSessionId, UUID attachmentId) {
        HttpRequest request = request(path(workSessionId.toString(), attachmentId, "content"))
                .header("X-Atenea-Synthetic-Fixture", "true")
                .DELETE()
                .build();
        HttpResponse<byte[]> response = send(request);
        requireSuccess(response);
        return read(response.body(), DeleteResult.class);
    }

    private <T> T json(String method, String path, byte[] body, Class<T> responseType) {
        HttpRequest.Builder builder = request(path);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpResponse<byte[]> response = send(builder.build());
        requireSuccess(response);
        return read(response.body(), responseType);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(properties.getEndpoint()) + path))
                .timeout(properties.getRequestTimeout())
                .header("Authorization", "Bearer " + readToken())
                .header("Accept", "application/json");
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw new AttachmentWorkerException(
                    "El almacenamiento de adjuntos no está disponible. Reintenta cuando AX42 esté accesible.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AttachmentWorkerException(
                    "La operación de adjuntos fue interrumpida; no se confirmó ningún cambio.",
                    exception);
        }
    }

    private void requireSuccess(HttpResponse<byte[]> response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        String code = "attachment_worker_rejected";
        try {
            JsonNode parsed = objectMapper.readTree(response.body());
            if (parsed.hasNonNull("error")) {
                code = parsed.get("error").asText(code);
            }
        } catch (IOException ignored) {
            // Keep a stable sanitized error when the worker response is malformed.
        }
        throw new AttachmentWorkerException(actionable(code), response.statusCode(), code);
    }

    private String actionable(String code) {
        return switch (code) {
            case "file_too_large" -> "El adjunto supera el límite de 16 MiB.";
            case "session_quota_exceeded" ->
                    "La WorkSession supera 256 MiB retenidos. Revisa su evidencia antes de continuar.";
            case "unsupported_content_type", "content_type_mismatch" ->
                    "El formato del adjunto no está permitido o no coincide con su contenido.";
            case "integrity_mismatch" ->
                    "El adjunto no superó la verificación de integridad; vuelve a seleccionarlo.";
            case "unauthorized" ->
                    "Atenea no puede autenticarse ante el almacenamiento de adjuntos.";
            case "attachment_identity_conflict", "ownership_state_conflict" ->
                    "La identidad del adjunto entra en conflicto con otro ownership.";
            case "attachment_not_found" -> "El adjunto no existe en esta WorkSession.";
            default -> "AX42 rechazó el adjunto de forma segura (" + code + ").";
        };
    }

    private <T> T read(byte[] body, Class<T> responseType) {
        try {
            return objectMapper.readValue(body, responseType);
        } catch (IOException exception) {
            throw new AttachmentWorkerException(
                    "AX42 devolvió una respuesta de adjuntos no compatible.",
                    exception);
        }
    }

    private String readToken() {
        try {
            if (properties.getTokenFile() == null || properties.getTokenFile().isBlank()) {
                throw new IOException("attachment token file is not configured");
            }
            String token = Files.readString(Path.of(properties.getTokenFile())).trim();
            if (token.length() < 32) {
                throw new IOException("attachment token file is invalid");
            }
            return token;
        } catch (IOException exception) {
            throw new AttachmentWorkerException(
                    "Atenea no tiene configurada la credencial privada de adjuntos.",
                    exception);
        }
    }

    private String path(String sessionIdentity, UUID attachmentId, String operation) {
        return "/v1/work-sessions/" + sessionIdentity
                + "/attachments/" + attachmentId + "/" + operation;
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Health(
            String protocolVersion,
            String workerId,
            boolean healthy,
            long maxFileBytes,
            long maxSessionBytes,
            List<String> contentTypes,
            Instant serverTime
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RealProjectCapability(
            String protocolVersion,
            String workerId,
            boolean healthy,
            List<String> projectIdentities,
            List<AttachmentStorageScope> storageScopes,
            Instant serverTime
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record StoredAttachment(
            String protocolVersion,
            String workerId,
            String sessionId,
            UUID attachmentId,
            String storageIdentity,
            AttachmentSource source,
            AttachmentKind kind,
            String contentType,
            long sizeBytes,
            AttachmentRetentionClass retentionClass,
            String sha256,
            boolean syntheticFixture,
            Instant createdAt,
            Instant storedAt
    ) {
    }

    public record PutResult(boolean created, StoredAttachment attachment) {
    }

    public record Content(String contentType, byte[] bytes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DeleteResult(
            String protocolVersion,
            String workerId,
            String sessionId,
            UUID attachmentId,
            boolean deleted,
            String sha256
    ) {
    }
}
