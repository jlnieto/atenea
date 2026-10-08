package com.atenea.github;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubOwnedHeadUfdTest {
    private static final String HEAD = "1".repeat(40), MAIN = "2".repeat(40);
    private static final String BRANCH = "atenea/change-59315b6e-59bc-4884-9def-356e1ca86ef4";
    private static final GitHubRepositoryRef REPO = new GitHubRepositoryRef("jlnieto", "atenea");
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> writes = new ArrayList<>();
    private HttpServer server;
    private GitHubClient client;
    private String pushRuns = "[]", dispatchRuns = "[]", comparison = "ahead";
    private boolean headWorkflow, mainController = true;
    private int contentError = 404;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            int code = 200;
            String response;
            if (exchange.getRequestMethod().equals("POST")) {
                writes.add(path + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                code = 204; response = "";
            } else if (path.endsWith("/runs")) {
                response = "{\"workflow_runs\":" + (query.contains("event=repository_dispatch") ? dispatchRuns : pushRuns) + "}";
            } else if (path.contains("/contents/")) {
                if (query.equals("ref=" + HEAD) && !headWorkflow) { code = contentError; response = "{\"message\":\"unavailable\"}"; }
                else response = "{\"encoding\":\"base64\",\"content\":\"" + Base64.getEncoder().encodeToString(
                        (mainController ? "repository_dispatch atenea-ufd-owned-head-v1" : "push only").getBytes(StandardCharsets.UTF_8)) + "\"}";
            } else if (path.contains("/compare/")) response = "{\"status\":\"" + comparison + "\"}";
            else if (path.endsWith("/git/ref/heads/main")) response = "{\"object\":{\"sha\":\"" + MAIN + "\"}}";
            else response = "{\"path\":\".github/workflows/ufd-validation-v1.yml\",\"state\":\"active\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, code == 204 ? -1 : bytes.length);
            if (code != 204) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        GitHubProperties properties = new GitHubProperties(); properties.setToken("synthetic-only");
        properties.setApiBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        client = new GitHubClient(mapper, properties);
    }
    @AfterEach void cleanup() { server.stop(0); }
    private String run(String event, String status, String conclusion) throws Exception {
        var run = mapper.createObjectNode().put("id", 14).put("event", event).put("status", status).put("conclusion", conclusion)
                .put("path", ".github/workflows/ufd-validation-v1.yml").put("head_branch", event.equals("push") ? BRANCH : "main")
                .put("head_sha", event.equals("push") ? HEAD : MAIN)
                .put("display_title", "Atenea UFD owned-head " + GitHubClient.ufdRequestId(HEAD, BRANCH) + " " + HEAD);
        run.putObject("head_repository").put("full_name", "jlnieto/atenea");
        return mapper.createArrayNode().add(run).toString();
    }
    @Test void successfulExactPushNeedsNoFallbackOrMutation() throws Exception {
        pushRuns = run("push", "completed", "success");
        client.requireUfdValidation(REPO, HEAD, BRANCH);
        assertTrue(writes.isEmpty());
    }
    @Test void missingWorkflowIsNotPretendedToBeRunning() {
        assertTrue(assertThrows(GitHubIntegrationException.class, () -> client.requireUfdValidation(REPO, HEAD, BRANCH))
                .getMessage().startsWith("UFD_WORKFLOW_MISSING:"));
        headWorkflow = true;
        assertTrue(assertThrows(GitHubIntegrationException.class, () -> client.requireUfdValidation(REPO, HEAD, BRANCH))
                .getMessage().startsWith("UFD_NOT_STARTED:"));
        assertTrue(writes.isEmpty());
    }
    @Test void closedDispatchUsesExactMainAuthorityAnd204WithoutNewPermissions() throws Exception {
        var request = client.prepareOwnedHeadUfd(HEAD, BRANCH);
        assertEquals(MAIN, request.authoritySha());
        assertEquals("7ff32e5c-9a66-3808-99e6-492123439f9d", request.requestId().toString());
        client.dispatchOwnedHeadUfd(request);
        assertEquals(1, writes.size());
        var body = mapper.readTree(writes.getFirst().substring(writes.getFirst().indexOf(' ') + 1));
        assertEquals("atenea-ufd-owned-head-v1", body.path("event_type").asText());
        assertEquals(4, body.path("client_payload").size());
        assertEquals(HEAD, body.path("client_payload").path("headSha").asText());
        assertEquals(BRANCH, body.path("client_payload").path("headBranch").asText());
        assertFalse(writes.getFirst().contains("command"));
    }
    @Test void unavailableControllerAndForeignBranchCannotDispatch() {
        mainController = false;
        assertEquals("UFD_CONTROLLER_UNAVAILABLE", assertThrows(GitHubIntegrationException.class,
                () -> client.prepareOwnedHeadUfd(HEAD, BRANCH)).getMessage());
        assertThrows(GitHubIntegrationException.class, () -> client.prepareOwnedHeadUfd(HEAD, "main"));
        assertTrue(writes.isEmpty());
    }
    @Test void completedMainRunIsBoundToOwnedHeadAndApprovedAuthority() throws Exception {
        dispatchRuns = run("repository_dispatch", "completed", "success");
        client.requireUfdValidation(REPO, HEAD, BRANCH);
        comparison = "diverged";
        assertEquals("UFD_IDENTITY_REJECTED", assertThrows(GitHubIntegrationException.class,
                () -> client.requireUfdValidation(REPO, HEAD, BRANCH)).getMessage());
        assertTrue(writes.isEmpty());
    }
    @Test void foreignHeadWorkflowOrTitleCannotSupplyPass() throws Exception {
        String correct = run("repository_dispatch", "completed", "success");
        for (String wrong : List.of(correct.replace("jlnieto/atenea", "foreign/atenea"),
                correct.replace("ufd-validation-v1.yml", "other.yml"), correct.replace(HEAD, "3".repeat(40)),
                correct.replace("\"main\"", "\"foreign\""))) {
            dispatchRuns = wrong;
            assertTrue(assertThrows(GitHubIntegrationException.class, () -> client.requireUfdValidation(REPO, HEAD, BRANCH))
                    .getMessage().startsWith("UFD_WORKFLOW_MISSING:"));
        }
    }
    @Test void failedOrRunningRunNeverAuthorizesPrAndFailedPushCannotBeBypassed() throws Exception {
        dispatchRuns = run("repository_dispatch", "in_progress", "");
        assertTrue(assertThrows(GitHubIntegrationException.class, () -> client.requireUfdValidation(REPO, HEAD, BRANCH))
                .getMessage().startsWith("UFD_PENDING:"));
        dispatchRuns = run("repository_dispatch", "completed", "failure");
        assertTrue(assertThrows(GitHubIntegrationException.class, () -> client.requireUfdValidation(REPO, HEAD, BRANCH))
                .getMessage().startsWith("UFD_FAILED:"));
        dispatchRuns = run("repository_dispatch", "completed", "success");
        pushRuns = run("push", "completed", "failure");
        assertTrue(assertThrows(GitHubIntegrationException.class, () -> client.requireUfdValidation(REPO, HEAD, BRANCH))
                .getMessage().startsWith("UFD_FAILED:"));
        assertTrue(writes.isEmpty());
    }
    @Test void deniedReadIsNotMisreportedAsMissingWorkflow() {
        contentError = 403;
        assertTrue(assertThrows(GitHubIntegrationException.class, () -> client.requireUfdValidation(REPO, HEAD, BRANCH))
                .getMessage().contains("not authorized"));
    }
    @Test void queuedRunIsRealButNotPretendedToBeExecuting() throws Exception {
        dispatchRuns=run("repository_dispatch","queued","");
        assertTrue(assertThrows(GitHubIntegrationException.class,()->client.requireUfdValidation(REPO,HEAD,BRANCH))
                .getMessage().startsWith("UFD_QUEUED:"));
    }
}
