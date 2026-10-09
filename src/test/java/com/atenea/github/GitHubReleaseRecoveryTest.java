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

class GitHubReleaseRecoveryTest {
    private static final String HEAD="1".repeat(40), MERGE="2".repeat(40), MAIN="3".repeat(40);
    private static final String BRANCH="atenea/change-test";
    private static final GitHubRepositoryRef REPO=new GitHubRepositoryRef("jlnieto","atenea");
    private final ObjectMapper mapper=new ObjectMapper();
    private final List<String> reads=new ArrayList<>(), writes=new ArrayList<>();
    private ObjectNode pr, comparison;
    private HttpServer server;
    private GitHubClient client;
    @BeforeEach void fixture() throws Exception {
        pr=mapper.createObjectNode().put("number",42).put("html_url","https://github.com/jlnieto/atenea/pull/42")
                .put("state","closed").put("merged",true).put("merge_commit_sha",MERGE);
        pr.putObject("base").put("ref","main").putObject("repo").put("full_name","jlnieto/atenea");
        pr.putObject("head").put("ref",BRANCH).put("sha",HEAD).putObject("repo").put("full_name","jlnieto/atenea");
        comparison=mapper.createObjectNode().put("status","ahead").put("ahead_by",1).put("behind_by",0).put("total_commits",1);
        comparison.putObject("base_commit").put("sha",MERGE);
        comparison.putObject("merge_base_commit").put("sha",MERGE);
        comparison.putArray("commits").addObject().put("sha",MAIN);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().toString();
            ("GET".equals(exchange.getRequestMethod())?reads:writes).add(path);
            byte[] response=mapper.writeValueAsBytes(path.contains("/compare/")?comparison:pr);
            exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start(); var properties=new GitHubProperties(); properties.setToken("synthetic-token");
        properties.setApiBaseUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
        client=new GitHubClient(mapper,properties);
    }
    @AfterEach void cleanup() { server.stop(0); }
    private void verify(String selected) { client.requireIntegratedChangeInSource(REPO,42,BRANCH,HEAD,MERGE,selected); }
    @Test void exactMergeAndProvenDescendantAreReadOnlyAndBoundToExactPr() {
        verify(MERGE); verify(MAIN);
        assertTrue(writes.isEmpty());
        assertEquals(List.of("/repos/jlnieto/atenea/pulls/42","/repos/jlnieto/atenea/pulls/42",
                "/repos/jlnieto/atenea/compare/"+MERGE+"..."+MAIN+"?per_page=100"),reads);
    }
    @Test void foreignUnmergedChangedHeadAndContradictoryMergeAreRejected() {
        ((ObjectNode)pr.path("head").path("repo")).put("full_name","foreign/atenea");
        assertThrows(GitHubIntegrationException.class,()->verify(MAIN));
        ((ObjectNode)pr.path("head").path("repo")).put("full_name","jlnieto/atenea");
        pr.put("merged",false); assertThrows(GitHubIntegrationException.class,()->verify(MAIN));
        pr.put("merged",true).put("merge_commit_sha","4".repeat(40));
        assertThrows(GitHubIntegrationException.class,()->verify(MAIN));
        pr.put("merge_commit_sha",MERGE); ((ObjectNode)pr.path("head")).put("sha","4".repeat(40));
        assertThrows(GitHubIntegrationException.class,()->verify(MAIN));
        assertTrue(writes.isEmpty()); assertTrue(reads.stream().noneMatch(path->path.contains("/compare/")));
    }
    @Test void behindDivergedOrWrongMergeBaseCannotAuthorizePublication() {
        for (String status:List.of("behind","diverged","identical")) {
            comparison.put("status",status);
            assertEquals("RELEASE_CHANGE_NOT_INCLUDED",assertThrows(GitHubIntegrationException.class,()->verify(MAIN)).getMessage());
        }
        comparison.put("status","ahead"); ((ObjectNode)comparison.path("merge_base_commit")).put("sha","4".repeat(40));
        assertEquals("RELEASE_CHANGE_NOT_INCLUDED",assertThrows(GitHubIntegrationException.class,()->verify(MAIN)).getMessage());
        assertTrue(writes.isEmpty());
    }
    @Test void truncatedComparisonOrDifferentDestinationIsNotAncestryEvidence() {
        comparison.put("ahead_by",101).put("total_commits",101);
        assertEquals("RELEASE_ANCESTRY_EVIDENCE_INCOMPLETE",assertThrows(GitHubIntegrationException.class,()->verify(MAIN)).getMessage());
        comparison.put("ahead_by",1).put("total_commits",1);
        ((ObjectNode)comparison.path("commits").path(0)).put("sha","4".repeat(40));
        assertEquals("RELEASE_ANCESTRY_EVIDENCE_INCOMPLETE",assertThrows(GitHubIntegrationException.class,()->verify(MAIN)).getMessage());
        assertTrue(writes.isEmpty());
    }
    @Test void invalidCommitIdentitiesAreRejectedBeforeAnyHttp() {
        for (String selected:List.of("main","../foreign","/bin/cmd","4".repeat(64))) {
            assertThrows(GitHubIntegrationException.class,()->verify(selected));
        }
        assertTrue(reads.isEmpty()); assertTrue(writes.isEmpty());
    }
}
