package com.atenea.github;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubDeliveryTest {
    private static final String SHA = "1".repeat(40), MERGE = "2".repeat(40), BRANCH = "atenea/change-test";
    private static final GitHubRepositoryRef REPO = new GitHubRepositoryRef("jlnieto","atenea");
    private final List<String> writes = new ArrayList<>();
    private HttpServer server;
    private GitHubClient client;
    private boolean draft, merged, foreign, checksFailed;
    private String mergeability = "clean";
    private String ufdConclusion = "success";
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String response;
            if (!method.equals("GET")) writes.add(method + " " + path + " " + new String(exchange.getRequestBody().readAllBytes()));
            if (path.endsWith("/runs")) response = """
                {"workflow_runs":[{"id":4,"head_sha":"%s","head_branch":"%s","event":"push",
                "head_repository":{"full_name":"jlnieto/atenea"},"path":".github/workflows/ufd-validation-v1.yml",
                "status":"completed","conclusion":"%s"}]}
                """.formatted(SHA,BRANCH,ufdConclusion);
            else if (path.endsWith("/check-runs")) response = "{\"total_count\":1,\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\""+(checksFailed?"failure":"success")+"\"}]}";
            else if (path.endsWith("/status")) response = "{\"total_count\":0,\"state\":\"pending\"}";
            else if (path.equals("/graphql")) { draft=false; response="{\"data\":{\"markPullRequestReadyForReview\":{\"pullRequest\":{\"isDraft\":false}}}}"; }
            else if (path.endsWith("/merge")) { merged=true; response="{\"merged\":true,\"sha\":\""+MERGE+"\"}"; }
            else response = """
                {"number":42,"html_url":"https://github.com/jlnieto/atenea/pull/42","state":"open",
                 "node_id":"PR_test","draft":%s,"merged":%s,"merge_commit_sha":"%s","mergeable":true,"mergeable_state":"%s",
                 "base":{"ref":"main","repo":{"full_name":"jlnieto/atenea"}},
                 "head":{"ref":"%s","sha":"%s","repo":{"full_name":"%s"}}}
                """.formatted(draft,merged,MERGE,mergeability,BRANCH,SHA,foreign?"foreign/atenea":"jlnieto/atenea");
            byte[] bytes=response.getBytes(); exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        GitHubProperties properties=new GitHubProperties(); properties.setToken("synthetic-token");
        properties.setApiBaseUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
        client=new GitHubClient(new ObjectMapper(),properties);
    }
    @AfterEach void cleanup() { server.stop(0); }
    @Test void exactMergeUsesShaPreconditionAndNoBypass() {
        assertEquals(MERGE,client.integrateExact(REPO,42,BRANCH,SHA));
        assertEquals(1,writes.size()); assertTrue(writes.getFirst().contains("\"sha\":\""+SHA+"\""));
        assertFalse(writes.getFirst().contains("force"));
    }
    @Test void lostMergeResponseReconcilesAlreadyMergedWithoutAnotherMutation() {
        merged=true;
        assertEquals(MERGE,client.integrateExact(REPO,42,BRANCH,SHA)); assertTrue(writes.isEmpty());
    }
    @Test void foreignPrAndChangedHeadRejectedBeforeWrites() {
        foreign=true; assertThrows(GitHubIntegrationException.class,()->client.integrateExact(REPO,42,BRANCH,SHA));
        foreign=false; assertThrows(GitHubIntegrationException.class,()->client.integrateExact(REPO,42,BRANCH,"3".repeat(40)));
        assertTrue(writes.isEmpty());
    }
    @Test void draftIsPreparedButNotMergedUntilFreshProtectionObservation() {
        draft=true; assertThrows(GitHubIntegrationException.class,()->client.integrateExact(REPO,42,BRANCH,SHA));
        assertEquals(1,writes.size()); assertTrue(writes.getFirst().contains("markPullRequestReadyForReview"));
        assertFalse(merged);
    }
    @Test void failedUfdFailedChecksAndBlockedMergeabilityCannotMerge() {
        ufdConclusion="failure"; assertThrows(GitHubIntegrationException.class,()->client.integrateExact(REPO,42,BRANCH,SHA));
        ufdConclusion="success"; checksFailed=true;
        assertThrows(GitHubIntegrationException.class,()->client.integrateExact(REPO,42,BRANCH,SHA));
        checksFailed=false; mergeability="blocked";
        assertThrows(GitHubIntegrationException.class,()->client.integrateExact(REPO,42,BRANCH,SHA));
        assertTrue(writes.isEmpty());
    }
}
