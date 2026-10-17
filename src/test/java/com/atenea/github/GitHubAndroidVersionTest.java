package com.atenea.github;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubAndroidVersionTest {
    private static final String BASE="1".repeat(40), HEAD="2".repeat(40), TREE="3".repeat(40), BLOB="4".repeat(40);
    private static final String FILE="android/app/build.gradle.kts", SOURCE="versionCode = 160\nversionName = \"0.5.127\"\n";
    private final ObjectMapper mapper=new ObjectMapper();
    private final List<String> writes=new ArrayList<>();
    private final UUID id=UUID.fromString("00000000-0000-4000-8000-000000000161");
    private HttpServer server; private GitHubClient client;
    private String main=BASE, retainedRef, content=SOURCE;
    private ObjectNode observedCommit;
    private ObjectNode createRun;
    @BeforeEach void setup() throws Exception {
        observedCommit=mapper.createObjectNode().put("sha",HEAD);
        observedCommit.putArray("parents").addObject().put("sha",BASE);
        observedCommit.putArray("files").addObject().put("filename",FILE).put("status","modified");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            String path=exchange.getRequestURI().toString(), method=exchange.getRequestMethod();
            JsonNode body=method.equals("GET") ? null : mapper.readTree(exchange.getRequestBody());
            if (!method.equals("GET")) writes.add(method+" "+path+" "+body);
            int status=200; JsonNode result;
            if (path.contains("/contents/")) {
                String value=path.endsWith(HEAD) ? content.replace("160","161").replace("0.5.127","0.5.128") : content;
                result=mapper.createObjectNode().put("type","file").put("path",FILE).put("size",value.length()).put("encoding","base64")
                        .put("content",Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } else if (path.endsWith("/git/ref/heads/main")) result=ref(main);
            else if (path.contains("/git/ref/heads/atenea/android-version-")) {
                result=ref(retainedRef); if (retainedRef==null) status=404;
            } else if (path.endsWith("/git/refs")) { retainedRef=body.path("sha").asText(); result=ref(retainedRef); }
            else if (path.endsWith("/git/commits/"+BASE)) { var value=mapper.createObjectNode(); value.putObject("tree").put("sha",TREE); result=value; }
            else if (path.endsWith("/git/blobs")) result=mapper.createObjectNode().put("sha",BLOB);
            else if (path.endsWith("/git/trees")) result=mapper.createObjectNode().put("sha",TREE);
            else if (path.endsWith("/git/commits")) result=mapper.createObjectNode().put("sha",HEAD);
            else if (path.endsWith("/commits/"+HEAD)) result=observedCommit;
            else if (path.contains("/actions/")) {
                var runs=mapper.createArrayNode();if (path.contains("event=create") && createRun!=null) runs.add(createRun);
                result=mapper.createObjectNode().set("workflow_runs",runs);
            }
            else { status=500;result=mapper.createObjectNode(); }
            byte[] response=mapper.writeValueAsBytes(result);exchange.sendResponseHeaders(status,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        var props=new GitHubProperties();props.setToken("synthetic-only");props.setApiBaseUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
        client=new GitHubClient(mapper,props);
    }
    @AfterEach void close() { server.stop(0); }
    private ObjectNode ref(String sha) { var value=mapper.createObjectNode();value.putObject("object").put("sha",sha);return value; }
    private String prepare() { return client.prepareAndroidVersion(id,BASE,new AndroidProductVersion(160,"0.5.127"),Instant.parse("2026-10-17T00:00:00Z")); }
    @Test void deterministicCommitTouchesOnlyTheFixedFileAndLostReplyReusesTheSameRef() throws Exception {
        assertEquals(HEAD,prepare());assertEquals(HEAD,prepare());
        assertEquals(1,writes.stream().filter(value->value.contains(" /repos/jlnieto/atenea/git/refs ")).count());
        assertTrue(writes.stream().noneMatch(value->value.startsWith("PATCH") || value.contains("refs/heads/main")));
        var trees=writes.stream().filter(value->value.contains("/git/trees ")).toList();
        for (String value:trees) { JsonNode body=mapper.readTree(value.substring(value.indexOf('{')));assertEquals(1,body.path("tree").size());assertEquals(FILE,body.path("tree").get(0).path("path").asText()); }
        var commits=writes.stream().filter(value->value.contains("/git/commits ")).toList();assertEquals(commits.get(0),commits.get(1));
        client.requireAndroidVersionHead(BASE,HEAD,new AndroidProductVersion(160,"0.5.127"));
    }
    @Test void movedMainAndMalformedVersionRejectBeforeAnyWrite() {
        main=HEAD;assertThrows(GitHubIntegrationException.class,this::prepare);assertTrue(writes.isEmpty());
        main=BASE;content+="versionCode = 161\n";assertThrows(GitHubIntegrationException.class,this::prepare);assertTrue(writes.isEmpty());
    }
    @Test void foreignRefIsNeverUpdatedOrForced() {
        retainedRef="5".repeat(40);assertEquals("ANDROID_VERSION_IDENTITY_MISMATCH",assertThrows(GitHubIntegrationException.class,this::prepare).getMessage());
        assertTrue(writes.stream().noneMatch(value->value.startsWith("PATCH") || value.contains("/git/refs ")));
    }
    @Test void foreignFilesParentsOrVersionCannotBeIntegrated() {
        observedCommit.withArray("files").addObject().put("filename","foreign.txt").put("status","modified");
        assertThrows(GitHubIntegrationException.class,()->client.requireAndroidVersionHead(BASE,HEAD,new AndroidProductVersion(160,"0.5.127")));
        observedCommit.withArray("files").remove(1);((ObjectNode)observedCommit.path("parents").get(0)).put("sha",HEAD);
        assertThrows(GitHubIntegrationException.class,()->client.requireAndroidVersionHead(BASE,HEAD,new AndroidProductVersion(160,"0.5.127")));
        assertTrue(writes.isEmpty());
    }
    @Test void noVersionPrIsCreatedBeforeTheExactHeadHasPassedUfd() {
        assertThrows(GitHubIntegrationException.class,()->client.androidVersionPullRequest(id,HEAD,new AndroidProductVersion(161,"0.5.128")));
        assertTrue(writes.isEmpty());
    }
    @Test void versionBranchCreateEventUsesExactHeadUfdAndRejectsForeignOrWrongEventEvidence() {
        String branch=GitHubClient.androidVersionBranch(id);
        createRun=mapper.createObjectNode().put("id",61).put("head_sha",HEAD).put("head_branch",branch)
                .put("path",".github/workflows/ufd-validation-v1.yml").put("event","create").put("status","completed").put("conclusion","success");
        createRun.putObject("head_repository").put("full_name","jlnieto/atenea");
        var repository=new GitHubRepositoryRef("jlnieto","atenea");
        assertDoesNotThrow(()->client.requireUfdValidation(repository,HEAD,branch));
        createRun.put("head_sha",BASE);assertThrows(GitHubIntegrationException.class,()->client.requireUfdValidation(repository,HEAD,branch));
        createRun.put("head_sha",HEAD).put("event","workflow_dispatch");assertThrows(GitHubIntegrationException.class,()->client.requireUfdValidation(repository,HEAD,branch));
        createRun.put("event","create").put("conclusion","failure");assertThrows(GitHubIntegrationException.class,()->client.requireUfdValidation(repository,HEAD,branch));
        assertTrue(writes.isEmpty());
    }
}
