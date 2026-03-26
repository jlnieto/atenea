package com.atenea.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitHubClient {

    private static final Pattern HTTPS_REMOTE = Pattern.compile("^https://github\\.com/([^/]+)/([^/.]+?)(?:\\.git)?$");
    private static final Pattern SSH_REMOTE = Pattern.compile("^git@github\\.com:([^/]+)/([^/.]+?)(?:\\.git)?$");

    private final ObjectMapper objectMapper;
    private final GitHubProperties properties;
    private final HttpClient httpClient;

    public GitHubClient(ObjectMapper objectMapper, GitHubProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    public GitHubPullRequest createPullRequest(
            GitHubRepositoryRef repository,
            String title,
            String body,
            String headBranch,
            String baseBranch
    ) {
        ensureConfigured();
        JsonNode response = sendJsonRequest(
                "POST",
                properties.getApiBaseUrl().resolve("/repos/" + repository.owner() + "/" + repository.repo() + "/pulls"),
                """
                        {
                          "title": %s,
                          "body": %s,
                          "head": %s,
                          "base": %s
                        }
                        """.formatted(
                        jsonString(title),
                        jsonString(body),
                        jsonString(headBranch),
                        jsonString(baseBranch)
                ));
        return toPullRequest(response);
    }

    public GitHubPullRequest getPullRequest(GitHubRepositoryRef repository, long pullRequestNumber) {
        ensureConfigured();
        JsonNode response = sendJsonRequest(
                "GET",
                properties.getApiBaseUrl().resolve("/repos/" + repository.owner() + "/" + repository.repo() + "/pulls/" + pullRequestNumber),
                null);
        return toPullRequest(response);
    }

    public GitHubRepositoryRef resolveRepository(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new GitHubIntegrationException("Git remote 'origin' is blank; cannot resolve GitHub repository");
        }

        Matcher httpsMatcher = HTTPS_REMOTE.matcher(remoteUrl.trim());
        if (httpsMatcher.matches()) {
            return new GitHubRepositoryRef(httpsMatcher.group(1), httpsMatcher.group(2));
        }

        Matcher sshMatcher = SSH_REMOTE.matcher(remoteUrl.trim());
        if (sshMatcher.matches()) {
            return new GitHubRepositoryRef(sshMatcher.group(1), sshMatcher.group(2));
        }

        throw new GitHubIntegrationException("Git remote '" + remoteUrl + "' is not a supported GitHub origin URL");
    }

    public long extractPullRequestNumber(String pullRequestUrl) {
        if (pullRequestUrl == null || pullRequestUrl.isBlank()) {
            throw new GitHubIntegrationException("Pull request URL is blank; cannot synchronize GitHub pull request");
        }

        String normalizedUrl = pullRequestUrl.trim();
        int marker = normalizedUrl.indexOf("/pull/");
        if (marker < 0) {
            throw new GitHubIntegrationException("Pull request URL '" + pullRequestUrl + "' is not a supported GitHub pull request URL");
        }

        String numberPart = normalizedUrl.substring(marker + "/pull/".length());
        int slashIndex = numberPart.indexOf('/');
        if (slashIndex >= 0) {
            numberPart = numberPart.substring(0, slashIndex);
        }

        try {
            return Long.parseLong(numberPart);
        } catch (NumberFormatException exception) {
            throw new GitHubIntegrationException("Pull request URL '" + pullRequestUrl + "' does not contain a valid pull request number");
        }
    }

    private void ensureConfigured() {
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            throw new GitHubIntegrationException("GitHub token is not configured");
        }

        Instant tokenExpiresAt = properties.getTokenExpiresAt();
        if (tokenExpiresAt != null && !tokenExpiresAt.isAfter(Instant.now())) {
            throw new GitHubIntegrationException("GitHub token is expired according to configuration (" + tokenExpiresAt + ")");
        }
    }

    private JsonNode sendJsonRequest(String method, URI uri, String body) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(properties.getReadTimeout())
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + properties.getToken())
                    .header("X-GitHub-Api-Version", "2022-11-28");

            if (body != null) {
                requestBuilder.header("Content-Type", "application/json");
            }

            HttpRequest request = requestBuilder.method(
                    method,
                    body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return objectMapper.readTree(response.body());
            }

            throw classifyError(response.statusCode(), response.body());
        } catch (GitHubIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GitHubIntegrationException("Failed to call GitHub: " + exception.getMessage(), exception);
        }
    }

    private GitHubIntegrationException classifyError(int statusCode, String responseBody) {
        String detail = extractErrorMessage(responseBody);
        if (statusCode == 401) {
            return new GitHubIntegrationException("GitHub token is invalid or expired: " + detail);
        }
        if (statusCode == 403) {
            return new GitHubIntegrationException("GitHub token is not authorized for this action or repository: " + detail);
        }
        if (statusCode == 404) {
            return new GitHubIntegrationException("GitHub repository or pull request was not found, or the token lacks access: " + detail);
        }
        if (statusCode == 422) {
            return new GitHubIntegrationException("GitHub rejected the pull request request: " + detail);
        }
        return new GitHubIntegrationException("GitHub request failed with status " + statusCode + ": " + detail);
    }

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);
            JsonNode message = json.get("message");
            if (message != null && !message.isNull() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (Exception ignored) {
        }
        return responseBody == null || responseBody.isBlank()
                ? "unknown GitHub error"
                : responseBody.replaceAll("\\s+", " ").trim();
    }

    private GitHubPullRequest toPullRequest(JsonNode json) {
        return new GitHubPullRequest(
                json.path("number").asLong(),
                json.path("html_url").asText(null),
                json.path("state").asText(null),
                json.path("merged").asBoolean(false)
        );
    }

    private String jsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new GitHubIntegrationException("Failed to encode GitHub JSON payload", exception);
        }
    }
}
