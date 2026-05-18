package com.atenea.codexappserver;

import com.atenea.api.mobile.MobileCodexAuthStatusResponse;
import com.atenea.service.worksession.WorkSessionOperationBlockedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CodexAuthStatusService {

    private final ObjectMapper objectMapper;
    private final CodexAppServerProperties primaryProperties;
    private final RescueCodexAppServerProperties rescueProperties;

    public CodexAuthStatusService(
            ObjectMapper objectMapper,
            @Qualifier("atenea.codex-app-server-com.atenea.codexappserver.CodexAppServerProperties")
            CodexAppServerProperties primaryProperties,
            RescueCodexAppServerProperties rescueProperties
    ) {
        this.objectMapper = objectMapper;
        this.primaryProperties = primaryProperties;
        this.rescueProperties = rescueProperties;
    }

    public List<MobileCodexAuthStatusResponse> getStatuses() {
        return List.of(
                readStatus("primary", primaryProperties.getAuthStatusFile(), primaryProperties.getAuthFile(), primaryProperties.getRequiredAuthMode()),
                readStatus("rescue", rescueProperties.getAuthStatusFile(), rescueProperties.getAuthFile(), rescueProperties.getRequiredAuthMode()));
    }

    public MobileCodexAuthStatusResponse getPrimaryStatus() {
        return readStatus("primary", primaryProperties.getAuthStatusFile(), primaryProperties.getAuthFile(), primaryProperties.getRequiredAuthMode());
    }

    public MobileCodexAuthStatusResponse getRescueStatus() {
        return readStatus("rescue", rescueProperties.getAuthStatusFile(), rescueProperties.getAuthFile(), rescueProperties.getRequiredAuthMode());
    }

    public void ensurePrimaryCompliant() {
        ensureCompliant(getPrimaryStatus(), "Codex App Server");
    }

    public void ensureRescueCompliant() {
        ensureCompliant(getRescueStatus(), "Codex Rescue App Server");
    }

    private void ensureCompliant(MobileCodexAuthStatusResponse status, String label) {
        if (!status.compliant()) {
            throw new WorkSessionOperationBlockedException(
                    "%s blocked: required %s auth, found %s (%s)"
                            .formatted(label, status.requiredAuthMode(), status.authMode(), status.status()));
        }
    }

    private MobileCodexAuthStatusResponse readStatus(String server, Path authStatusFile, Path authFile, String requiredAuthMode) {
        String required = normalize(requiredAuthMode, "chatgpt");
        if ("disabled".equals(required)) {
            return new MobileCodexAuthStatusResponse(server, false, true, "guard_disabled", required, null, false, false);
        }
        if (authStatusFile != null && Files.isRegularFile(authStatusFile)) {
            return readSanitizedStatus(server, authStatusFile, required);
        }
        if (authFile == null) {
            return new MobileCodexAuthStatusResponse(server, false, false, "auth_file_not_configured", required, null, false, false);
        }
        if (!Files.isRegularFile(authFile)) {
            return new MobileCodexAuthStatusResponse(server, true, false, "auth_file_not_found", required, null, false, false);
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(authFile));
            String authMode = text(root.path("auth_mode"));
            boolean apiKeyPresent = hasText(root.path("OPENAI_API_KEY"));
            JsonNode tokens = root.path("tokens");
            boolean tokensPresent = hasText(tokens.path("access_token")) && hasText(tokens.path("refresh_token"));
            boolean compliant = required.equals(authMode)
                    && (!"chatgpt".equals(required) || (!apiKeyPresent && tokensPresent));
            String status = compliant ? "ok" : "auth_mode_mismatch";
            if ("chatgpt".equals(required) && apiKeyPresent) {
                status = "api_key_present";
            } else if ("chatgpt".equals(required) && !tokensPresent) {
                status = "tokens_missing";
            }
            return new MobileCodexAuthStatusResponse(
                    server,
                    true,
                    compliant,
                    status,
                    required,
                    authMode,
                    apiKeyPresent,
                    tokensPresent);
        } catch (Exception exception) {
            return new MobileCodexAuthStatusResponse(
                    server,
                    true,
                    false,
                    "auth_file_read_error",
                    required,
                    null,
                    false,
                    false);
        }
    }

    private MobileCodexAuthStatusResponse readSanitizedStatus(String server, Path authStatusFile, String required) {
        try {
            JsonNode root = objectMapper.readTree(Files.readString(authStatusFile));
            String status = text(root.path("status"));
            String authMode = text(root.path("authMode"));
            boolean apiKeyPresent = root.path("apiKeyPresent").asBoolean(false);
            boolean tokensPresent = root.path("tokensPresent").asBoolean(false);
            boolean compliant = root.path("compliant").asBoolean(false)
                    && required.equals(normalize(text(root.path("requiredAuthMode")), required))
                    && (!"chatgpt".equals(required) || (authMode != null && !apiKeyPresent && tokensPresent));
            return new MobileCodexAuthStatusResponse(
                    server,
                    true,
                    compliant,
                    status == null ? (compliant ? "ok" : "status_file_invalid") : status,
                    required,
                    authMode,
                    apiKeyPresent,
                    tokensPresent);
        } catch (Exception exception) {
            return new MobileCodexAuthStatusResponse(
                    server,
                    true,
                    false,
                    "auth_status_file_read_error",
                    required,
                    null,
                    false,
                    false);
        }
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase();
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().trim().toLowerCase() : null;
    }

    private boolean hasText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }
}
