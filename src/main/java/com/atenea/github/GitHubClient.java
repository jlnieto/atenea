package com.atenea.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitHubClient {
    private static final String UFD_WORKFLOW = ".github/workflows/ufd-validation-v1.yml";
    private static final String UFD_DISPATCH = "atenea-ufd-owned-head-v1";

    private static final Pattern HTTPS_REMOTE = Pattern.compile("^https://github\\.com/([^/]+)/([^/.]+?)(?:\\.git)?$");
    private static final Pattern SSH_REMOTE = Pattern.compile("^git@github\\.com:([^/]+)/([^/.]+?)(?:\\.git)?$");

    private final ObjectMapper objectMapper;
    private final GitHubProperties properties;
    private final HttpClient httpClient;
    private volatile String fileToken;

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
                          "base": %s,
                          "draft": true
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

    /** A push run, or the closed main-controller run bound to the exact owned head. */
    public void requireUfdValidation(GitHubRepositoryRef repository, String sha, String branch) {
        ensureConfigured();
        requireSha(sha);
        String repo = repository.owner() + "/" + repository.repo();
        JsonNode runs = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repo + "/actions/workflows/ufd-validation-v1.yml/runs?head_sha="
                        + sha + "&branch=" + encode(branch) + "&event=push&per_page=100"), null);
        if (!runs.path("workflow_runs").isArray() || runs.path("workflow_runs").size() >= 100) {
            throw new GitHubIntegrationException("UFD_EVIDENCE_INCOMPLETE");
        }
        JsonNode latest = null;
        for (JsonNode run : runs.path("workflow_runs")) {
            if (sha.equals(run.path("head_sha").asText())
                    && branch.equals(run.path("head_branch").asText())
                    && repo.equals(run.path("head_repository").path("full_name").asText())
                    && UFD_WORKFLOW.equals(run.path("path").asText())
                    && "push".equals(run.path("event").asText())
                    && (latest == null || run.path("id").asLong() > latest.path("id").asLong())) latest = run;
        }
        if (latest == null && ownedHead(repository, branch)) {
            latest = ownedHeadUfdRun(repository, sha, branch);
        }
        if (latest == null) {
            JsonNode workflow = content(repository, UFD_WORKFLOW, sha);
            if (workflow.isMissingNode()) {
                throw new GitHubIntegrationException("UFD_WORKFLOW_MISSING: la rama publicada no contiene el disparador UFD");
            }
            throw new GitHubIntegrationException("UFD_NOT_STARTED: GitHub aún no ha registrado la comprobación de este commit");
        }
        if ("completed".equals(latest.path("status").asText())
                && "success".equals(latest.path("conclusion").asText())) return;
        if ("completed".equals(latest.path("status").asText())) {
            throw new GitHubIntegrationException("UFD_FAILED: la validación del commit publicado ha fallado");
        }
        if (List.of("queued", "waiting", "requested", "pending").contains(latest.path("status").asText())) {
            throw new GitHubIntegrationException("UFD_QUEUED: GitHub ha registrado el run, pendiente de ejecución");
        }
        if (!"in_progress".equals(latest.path("status").asText())) {
            throw new GitHubIntegrationException("UFD_EVIDENCE_INCOMPLETE");
        }
        throw new GitHubIntegrationException("UFD_PENDING: validación GitHub pendiente para este commit; vuelve a consultar sin crear otra PR");
    }

    public record UfdDispatchRequest(UUID requestId, String headSha, String headBranch, String authoritySha) { }

    public UfdDispatchRequest prepareOwnedHeadUfd(String sha, String branch) {
        requireSha(sha);
        GitHubRepositoryRef repository = new GitHubRepositoryRef("jlnieto", "atenea");
        if (!ownedHead(repository, branch)) throw new GitHubIntegrationException("UFD_IDENTITY_REJECTED");
        String authority = canonicalMain(repository);
        JsonNode file = content(repository, UFD_WORKFLOW, authority);
        if (file.isMissingNode()) throw new GitHubIntegrationException("UFD_CONTROLLER_UNAVAILABLE");
        JsonNode workflow = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/jlnieto/atenea/actions/workflows/ufd-validation-v1.yml"), null, true);
        if (!"active".equals(workflow.path("state").asText())
                || !UFD_WORKFLOW.equals(workflow.path("path").asText())
                || !"base64".equals(file.path("encoding").asText())
                || !new String(Base64.getMimeDecoder().decode(file.path("content").asText()), StandardCharsets.UTF_8)
                        .contains(UFD_DISPATCH)) {
            throw new GitHubIntegrationException("UFD_CONTROLLER_UNAVAILABLE");
        }
        return new UfdDispatchRequest(ufdRequestId(sha, branch), sha, branch, authority);
    }

    /** Called only after the delivery outbox has durably claimed this one send. */
    public void dispatchOwnedHeadUfd(UfdDispatchRequest request) {
        requireSha(request.headSha()); requireSha(request.authoritySha());
        if (!ownedHead(new GitHubRepositoryRef("jlnieto", "atenea"), request.headBranch())
                || !ufdRequestId(request.headSha(), request.headBranch()).equals(request.requestId())) {
            throw new GitHubIntegrationException("UFD_IDENTITY_REJECTED");
        }
        var payload = objectMapper.createObjectNode();
        payload.put("event_type", UFD_DISPATCH);
        payload.putObject("client_payload").put("requestId", request.requestId().toString())
                .put("headSha", request.headSha()).put("headBranch", request.headBranch())
                .put("authoritySha", request.authoritySha());
        sendJsonRequest("POST", properties.getApiBaseUrl().resolve("/repos/jlnieto/atenea/dispatches"), payload.toString());
    }

    public static UUID ufdRequestId(String sha, String branch) {
        return UUID.nameUUIDFromBytes((UFD_DISPATCH + "|jlnieto/atenea|" + branch + "|" + sha)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static boolean ownedHead(GitHubRepositoryRef repository, String branch) {
        return "jlnieto".equals(repository.owner()) && "atenea".equals(repository.repo())
                && branch != null && branch.matches("atenea/change-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private JsonNode ownedHeadUfdRun(GitHubRepositoryRef repository, String sha, String branch) {
        String title = "Atenea UFD owned-head " + ufdRequestId(sha, branch) + " " + sha;
        JsonNode latest = null;
        // An immutable run is discoverable even if the dispatch HTTP reply was lost.
        // Bound pagination: incomplete evidence is never a PASS or an endless pending.
        for (int page = 1; page <= 10; page++) {
            JsonNode runs = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                    "/repos/jlnieto/atenea/actions/workflows/ufd-validation-v1.yml/runs?event=repository_dispatch&per_page=100&page=" + page), null);
            if (!runs.path("workflow_runs").isArray()) throw new GitHubIntegrationException("UFD_EVIDENCE_INCOMPLETE");
            for (JsonNode run : runs.path("workflow_runs")) {
                if (title.equals(run.path("display_title").asText())
                        && "main".equals(run.path("head_branch").asText())
                        && "jlnieto/atenea".equals(run.path("head_repository").path("full_name").asText())
                        && UFD_WORKFLOW.equals(run.path("path").asText())
                        && "repository_dispatch".equals(run.path("event").asText())
                        && (latest == null || run.path("id").asLong() > latest.path("id").asLong())) latest = run;
            }
            if (runs.path("workflow_runs").size() < 100) break;
            if (page == 10) throw new GitHubIntegrationException("UFD_EVIDENCE_INCOMPLETE");
        }
        if (latest != null) {
            String authority = latest.path("head_sha").asText(); requireSha(authority);
            String main = canonicalMain(repository);
            JsonNode comparison = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                    "/repos/jlnieto/atenea/compare/" + authority + "..." + main), null);
            if (!List.of("identical", "ahead").contains(comparison.path("status").asText())) {
                throw new GitHubIntegrationException("UFD_IDENTITY_REJECTED");
            }
        }
        return latest;
    }

    private JsonNode content(GitHubRepositoryRef repository, String path, String ref) {
        return sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repository.owner() + "/" + repository.repo() + "/contents/" + path + "?ref=" + ref), null, true);
    }

    public String canonicalMain(GitHubRepositoryRef repository) {
        ensureConfigured();
        String sha = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repository.owner() + "/" + repository.repo() + "/git/ref/heads/main"), null)
                .path("object").path("sha").asText();
        requireSha(sha);
        return sha;
    }

    /** Read-only observation of the exact server-owned PR; never marks a draft ready. */
    public GitHubMergeState observeMergeState(GitHubRepositoryRef repository, long number, String headBranch, String headSha) {
        ensureConfigured();
        requireSha(headSha);
        JsonNode pr = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repository.owner() + "/" + repository.repo() + "/pulls/" + number), null);
        requireExactPullRequest(repository, number, headBranch, headSha, pr);
        return GitHubMergeState.observe(pr);
    }

    /** No caller-selected PR/head/base and no protection bypass. Lost responses reconcile by GET. */
    public String integrateExact(GitHubRepositoryRef repository, long number, String headBranch, String headSha) {
        ensureConfigured();
        requireSha(headSha);
        String path = "/repos/" + repository.owner() + "/" + repository.repo() + "/pulls/" + number;
        JsonNode pr = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(path), null);
        requireExactPullRequest(repository, number, headBranch, headSha, pr);
        if (pr.path("merged").asBoolean()) {
            String sha = pr.path("merge_commit_sha").asText();
            requireSha(sha);
            return sha;
        }
        if (!"open".equals(pr.path("state").asText())) {
            throw new GitHubIntegrationException("PR_CLOSED: la PR está cerrada sin integrar");
        }
        if (GitHubMergeState.observe(pr) == GitHubMergeState.CONFLICTS) {
            throw new GitHubIntegrationException("PR_MERGE_CONFLICTS: hay conflictos que resolver antes de integrar");
        }
        requireUfdValidation(repository, headSha, headBranch);
        if (pr.path("draft").asBoolean()) {
            String nodeId = pr.path("node_id").asText();
            if (nodeId.isBlank()) throw new GitHubIntegrationException("PR_IDENTITY_INVALID");
            JsonNode ready = sendJsonRequest("POST", properties.getApiBaseUrl().resolve("/graphql"),
                    "{\"query\":\"mutation($id:ID!){markPullRequestReadyForReview(input:{pullRequestId:$id}){pullRequest{isDraft}}}\",\"variables\":{\"id\":"
                            + jsonString(nodeId) + "}}");
            JsonNode draft = ready.path("data").path("markPullRequestReadyForReview").path("pullRequest").path("isDraft");
            if (ready.has("errors") || !draft.isBoolean() || draft.asBoolean()) {
                throw new GitHubIntegrationException("PR_READY_REJECTED");
            }
            throw new GitHubIntegrationException("CI_PENDING: PR preparada para revisión; comprobando protecciones");
        }
        if (GitHubMergeState.observe(pr) == GitHubMergeState.UNKNOWN) {
            throw new GitHubIntegrationException("PR_MERGEABILITY_PENDING: GitHub aún calcula si esta PR puede integrarse");
        }
        if (GitHubMergeState.observe(pr) != GitHubMergeState.MERGEABLE
                || !"clean".equals(pr.path("mergeable_state").asText())) {
            throw new GitHubIntegrationException("PR_PROTECTED: las protecciones no permiten integrar este commit");
        }
        JsonNode checks = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repository.owner() + "/" + repository.repo() + "/commits/" + headSha
                        + "/check-runs?per_page=100&filter=latest"), null);
        if (checks.path("total_count").asInt(-1) < 0 || checks.path("total_count").asInt() > 100) {
            throw new GitHubIntegrationException("CI_EVIDENCE_INCOMPLETE");
        }
        for (JsonNode check : checks.path("check_runs")) {
            if ("completed".equals(check.path("status").asText())
                    && !java.util.Set.of("success", "neutral", "skipped").contains(check.path("conclusion").asText())) {
                throw new GitHubIntegrationException("CI_FAILED: una comprobación ha fallado");
            }
            if (!"completed".equals(check.path("status").asText())) {
                throw new GitHubIntegrationException("CI_PENDING: una comprobación no ha terminado correctamente");
            }
        }
        JsonNode statuses = sendJsonRequest("GET", properties.getApiBaseUrl().resolve(
                "/repos/" + repository.owner() + "/" + repository.repo() + "/commits/" + headSha + "/status"), null);
        if (java.util.Set.of("failure", "error").contains(statuses.path("state").asText())) {
            throw new GitHubIntegrationException("CI_FAILED: un estado de integración ha fallado");
        }
        if (statuses.path("total_count").asInt(-1) < 0
                || (statuses.path("total_count").asInt() > 0 && !"success".equals(statuses.path("state").asText()))) {
            throw new GitHubIntegrationException("CI_PENDING: estado de integración pendiente");
        }
        JsonNode merged = sendJsonRequest("PUT", properties.getApiBaseUrl().resolve(path + "/merge"),
                "{\"sha\":" + jsonString(headSha) + ",\"merge_method\":\"merge\"}");
        if (!merged.path("merged").asBoolean()) throw new GitHubIntegrationException("MERGE_REJECTED");
        String sha = merged.path("sha").asText();
        requireSha(sha);
        return sha;
    }

    private void requireExactPullRequest(GitHubRepositoryRef repository, long number, String branch, String sha, JsonNode pr) {
        String repo = repository.owner() + "/" + repository.repo();
        if (number <= 0 || number != pr.path("number").asLong()
                || !repo.equals(pr.path("base").path("repo").path("full_name").asText())
                || !repo.equals(pr.path("head").path("repo").path("full_name").asText())
                || !"main".equals(pr.path("base").path("ref").asText())
                || !branch.equals(pr.path("head").path("ref").asText())
                || !sha.equals(pr.path("head").path("sha").asText())
                || !("https://github.com/" + repo + "/pull/" + number).equals(pr.path("html_url").asText())) {
            throw new GitHubIntegrationException("PR_OWNERSHIP_MISMATCH");
        }
    }

    private static void requireSha(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) throw new GitHubIntegrationException("COMMIT_IDENTITY_INVALID");
    }

    public List<GitHubPullRequest> findOpenPullRequests(
            GitHubRepositoryRef repository,
            String headBranch,
            String baseBranch) {
        ensureConfigured();
        String query = "state=open&head=" + encode(repository.owner() + ":" + headBranch)
                + "&base=" + encode(baseBranch);
        JsonNode response = sendJsonRequest(
                "GET",
                properties.getApiBaseUrl().resolve(
                        "/repos/" + repository.owner() + "/" + repository.repo()
                                + "/pulls?" + query),
                null);
        if (!response.isArray()) {
            throw new GitHubIntegrationException(
                    "GitHub did not return a pull request collection");
        }
        List<GitHubPullRequest> result = new ArrayList<>();
        response.forEach(item -> result.add(toPullRequest(item)));
        return List.copyOf(result);
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
        if (configuredToken() == null) {
            throw new GitHubIntegrationException("GitHub token is not configured");
        }

        Instant tokenExpiresAt = properties.getTokenExpiresAt();
        if (tokenExpiresAt != null && !tokenExpiresAt.isAfter(Instant.now())) {
            throw new GitHubIntegrationException("GitHub token is expired according to configuration (" + tokenExpiresAt + ")");
        }
    }

    private JsonNode sendJsonRequest(String method, URI uri, String body) {
        return sendJsonRequest(method, uri, body, false);
    }

    private JsonNode sendJsonRequest(String method, URI uri, String body, boolean missingAllowed) {
        try {
            String token = configuredToken();
            if (token == null) {
                throw new GitHubIntegrationException("GitHub token is not configured");
            }
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(properties.getReadTimeout())
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
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
                if (response.statusCode() == 204) return objectMapper.nullNode();
                return objectMapper.readTree(response.body());
            }
            if (missingAllowed && response.statusCode() == 404) return objectMapper.missingNode();

            throw classifyError(response.statusCode(), response.body());
        } catch (GitHubIntegrationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GitHubIntegrationException("Failed to call GitHub: " + exception.getMessage(), exception);
        }
    }

    private String configuredToken() {
        String configured = properties.getToken();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (fileToken != null) {
            return fileToken;
        }
        String tokenFile = properties.getTokenFile();
        if (tokenFile == null || tokenFile.isBlank()) {
            return null;
        }
        synchronized (this) {
            if (fileToken != null) {
                return fileToken;
            }
            try {
                String loaded = Files.readString(Path.of(tokenFile.trim())).trim();
                if (!loaded.isBlank()) {
                    fileToken = loaded;
                }
                return fileToken;
            } catch (Exception exception) {
                throw new GitHubIntegrationException("GitHub token file could not be read", exception);
            }
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
                json.path("merged").asBoolean(false),
                json.path("base").path("repo").path("full_name").asText(null),
                json.path("base").path("ref").asText(null),
                json.path("head").path("repo").path("full_name").asText(null),
                json.path("head").path("ref").asText(null),
                json.path("head").path("sha").asText(null),
                json.path("draft").asBoolean(false)
        );
    }

    private String jsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new GitHubIntegrationException("Failed to encode GitHub JSON payload", exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
