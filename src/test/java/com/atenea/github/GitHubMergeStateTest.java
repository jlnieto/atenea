package com.atenea.github;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubMergeStateTest {
    private static final String HEAD = "1".repeat(40);
    private static final String BRANCH = "atenea/change-59315b6e-59bc-4884-9def-356e1ca86ef4";
    private static final GitHubRepositoryRef REPO = new GitHubRepositoryRef("jlnieto", "atenea");
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> requests = new ArrayList<>();
    private HttpServer server;
    private GitHubClient client;
    private ObjectNode pr;

    @BeforeEach void setup() throws Exception {
        pr = mapper.createObjectNode().put("number", 47).put("state", "open").put("merged", false)
                .put("draft", true).put("mergeable", false).put("mergeable_state", "dirty")
                .put("html_url", "https://github.com/jlnieto/atenea/pull/47");
        pr.putObject("head").put("ref", BRANCH).put("sha", HEAD)
                .putObject("repo").put("full_name", "jlnieto/atenea");
        pr.putObject("base").put("ref", "main").putObject("repo").put("full_name", "jlnieto/atenea");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] body = mapper.writeValueAsBytes(pr);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var properties = new GitHubProperties(); properties.setToken("synthetic-only");
        properties.setApiBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        client = new GitHubClient(mapper, properties);
    }
    @AfterEach void cleanup() { server.stop(0); }
    private GitHubMergeState observe() { return client.observeMergeState(REPO, 47, BRANCH, HEAD); }

    @Test void exactDraftConflictIsObservedWithoutWritingOrStartingChecks() {
        assertEquals(GitHubMergeState.CONFLICTS, observe());
        assertEquals(List.of("GET /repos/jlnieto/atenea/pulls/47"), requests);
    }
    @Test void integrationRejectsConflictBeforeMarkingDraftReadyOrStartingValidation() {
        assertTrue(assertThrows(GitHubIntegrationException.class,
                () -> client.integrateExact(REPO, 47, BRANCH, HEAD)).getMessage().startsWith("PR_MERGE_CONFLICTS:"));
        assertEquals(List.of("GET /repos/jlnieto/atenea/pulls/47"), requests);
    }
    @Test void nullMergeabilityIsAnObservationPendingNotAConflictOrRunningCi() {
        pr.putNull("mergeable").put("mergeable_state", "unknown");
        assertEquals(GitHubMergeState.UNKNOWN, observe());
    }
    @Test void explicitDirtyStateRemainsConflictEvenIfMergeabilityIsBeingRecalculated() {
        pr.putNull("mergeable");
        assertEquals(GitHubMergeState.CONFLICTS, observe());
    }
    @Test void protectedStateCannotBeAdvertisedAsMergeable() {
        pr.put("mergeable", true).put("mergeable_state", "blocked");
        assertEquals(GitHubMergeState.PROTECTED, observe());
    }
    @Test void cleanMergeabilityDoesNotPublishOrIntegrateAnything() {
        pr.put("mergeable", true).put("mergeable_state", "clean");
        assertEquals(GitHubMergeState.MERGEABLE, observe());
        assertEquals(1, requests.size());
    }
    @Test void falseMergeableCannotBeTurnedIntoReadyByCleanText() {
        pr.put("mergeable_state", "clean");
        assertEquals(GitHubMergeState.PROTECTED, observe());
    }
    @Test void closedAndMergedAreNotConfusedWithOpenIntegration() {
        pr.put("state", "closed");
        assertEquals(GitHubMergeState.CLOSED, observe());
        pr.put("merged", true);
        assertEquals(GitHubMergeState.MERGED, observe());
    }
    @Test void foreignHeadOrRepositoryCannotSupplyAnObservation() {
        ((ObjectNode)pr.path("head")).put("sha", "2".repeat(40));
        assertEquals("PR_OWNERSHIP_MISMATCH", assertThrows(GitHubIntegrationException.class, this::observe).getMessage());
        ((ObjectNode)pr.path("head")).put("sha", HEAD);
        ((ObjectNode)pr.path("base").path("repo")).put("full_name", "foreign/atenea");
        assertThrows(GitHubIntegrationException.class, this::observe);
        assertTrue(requests.stream().allMatch(value -> value.startsWith("GET ")));
    }
    @Test void malformedPrStateDoesNotBecomeAFalseClosedOrReadyObservation() {
        pr.remove("state");
        assertEquals("PR_EVIDENCE_INCOMPLETE", assertThrows(GitHubIntegrationException.class, this::observe).getMessage());
    }
}
