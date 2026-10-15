package com.atenea.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubClientTest {

    private static final String HEAD="6".repeat(40), MERGE="7".repeat(40);
    private static String exactPr(int count) {
        return """
                {"number":42,"title":"Mejoras de Atenea","html_url":"https://github.com/jlnieto/atenea/pull/42",
                 "state":"closed","merged":true,"merged_at":"2026-09-01T09:00:00Z","changed_files":%d,
                 "merge_commit_sha":"%s","base":{"ref":"main","repo":{"full_name":"jlnieto/atenea"}},
                 "head":{"ref":"feature/news","sha":"%s","repo":{"full_name":"jlnieto/atenea"}}}
                """.formatted(count,MERGE,HEAD);
    }
    private void withFixture(Function<String,String> replies,java.util.function.Consumer<GitHubClient> test) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            byte[] response=replies.apply(exchange.getRequestURI().toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var properties=new GitHubProperties();properties.setToken("ephemeral-test-token");
            properties.setApiBaseUrl(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
            test.accept(new GitHubClient(new ObjectMapper(),properties));
        } finally { server.stop(0); }
    }

    @Test void changedFileEvidenceIncludesBothSidesOfRename() throws Exception {
        withFixture(path -> path.contains("/files?") ? "[{\"filename\":\"src/main/Moved.java\",\"previous_filename\":\"android/app/Old.kt\"}]" : exactPr(1),
                client -> assertThat(client.changedFiles(new GitHubRepositoryRef("jlnieto","atenea"),42,"feature/news",HEAD))
                        .containsExactly("android/app/Old.kt","src/main/Moved.java"));
    }
    @Test void truncatedDuplicateOrUnsafeFileEvidenceCannotClassifyARelease() throws Exception {
        for (String files:List.of("[]","[{\"filename\":\"android/app/A.kt\"}]",
                "[{\"filename\":\"android/app/A.kt\"},{\"filename\":\"android/app/A.kt\"}]",
                "[{\"filename\":\"../other\"},{\"filename\":\"src/A.java\"}]")) {
            withFixture(path -> path.contains("/files?") ? files : exactPr(2),client ->
                    org.junit.jupiter.api.Assertions.assertThrows(GitHubIntegrationException.class,() -> client.changedFiles(
                            new GitHubRepositoryRef("jlnieto","atenea"),42,"feature/news",HEAD)));
        }
    }
    @Test void movingHeadDuringDiffReadIsRejected() throws Exception {
        var reads=new AtomicInteger();
        withFixture(path -> path.contains("/files?") ? "[{\"filename\":\"android/app/A.kt\"}]"
                : reads.incrementAndGet()==1 ? exactPr(1) : exactPr(1).replace(HEAD,"9".repeat(40)),client ->
                org.junit.jupiter.api.Assertions.assertThrows(GitHubIntegrationException.class,() -> client.changedFiles(
                        new GitHubRepositoryRef("jlnieto","atenea"),42,"feature/news",HEAD)));
    }
    @Test void globalNewsReadsOnlyMergedOwnedPullRequestsAndExplicitProductSummary() throws Exception {
        var mapper=new ObjectMapper();var pr=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(exactPr(1));
        pr.put("body","## Internal\nNever display this internal section.\n## Novedades para el usuario\n- Imágenes en la misma conversación.\n- Estado claro de la publicación.\n- sudo dangerous /srv/private\n## Tests\nInternal test details");
        String response=mapper.createArrayNode().add(pr).add(pr.deepCopy().putNull("merged_at")).toString();
        withFixture(path -> response,client -> {
            var result=client.recentMergedChanges(new GitHubRepositoryRef("jlnieto","atenea"),1);
            assertThat(result.changes()).hasSize(1);assertThat(result.changes().getFirst().summary())
                    .isEqualTo("Imágenes en la misma conversación.\nEstado claro de la publicación.");assertThat(result.more()).isFalse();
        });
    }
    @Test void historyNeverTrustsForeignRepositoryOrUnsafeProductText() throws Exception {
        withFixture(path -> "["+exactPr(1).replace("jlnieto/atenea","foreign/atenea")+"]",client ->
                org.junit.jupiter.api.Assertions.assertThrows(GitHubIntegrationException.class,() -> client.recentMergedChanges(
                        new GitHubRepositoryRef("jlnieto","atenea"),1)));
        assertThat(GitHubClient.productText("secret /home/operator/token",200)).isEmpty();
        assertThat(GitHubClient.productSummary("## Internal\nPrivate details","Mejora de Atenea")).isEqualTo("Mejora de Atenea");
    }

    @TempDir
    Path tempDir;

    @Test
    void readsNamedTokenFileWithoutPuttingTheSecretInConfiguration() throws Exception {
        Path tokenFile = tempDir.resolve("github-token");
        Files.writeString(tokenFile, "ephemeral-test-token\n");
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {
                      "number":17,
                      "html_url":"https://github.com/jlnieto/atenea/pull/17",
                      "state":"open",
                      "merged":false,
                      "base":{"ref":"main","repo":{"full_name":"jlnieto/atenea"}},
                      "head":{"ref":"atenea/session-17","sha":"abc123","repo":{"full_name":"jlnieto/atenea"}}
                    }
                    """.getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            GitHubProperties properties = new GitHubProperties();
            properties.setApiBaseUrl(new java.net.URI(
                    "http://127.0.0.1:" + server.getAddress().getPort()));
            properties.setTokenFile(tokenFile.toString());

            GitHubPullRequest pullRequest = new GitHubClient(new ObjectMapper(), properties)
                    .getPullRequest(new GitHubRepositoryRef("jlnieto", "atenea"), 17);

            assertThat(pullRequest.number()).isEqualTo(17);
            assertThat(pullRequest.baseRepository()).isEqualTo("jlnieto/atenea");
            assertThat(pullRequest.baseRef()).isEqualTo("main");
            assertThat(pullRequest.headRepository()).isEqualTo("jlnieto/atenea");
            assertThat(pullRequest.headRef()).isEqualTo("atenea/session-17");
            assertThat(pullRequest.headSha()).isEqualTo("abc123");
            assertThat(authorization.get()).isEqualTo("Bearer ephemeral-test-token");
            assertThat(properties.getToken()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void findsExactOpenDraftByServerOwnedHeadAndBase() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] response = """
                    [{
                      "number":42,
                      "html_url":"https://github.com/jlnieto/atenea/pull/42",
                      "state":"open",
                      "draft":true,
                      "merged":false,
                      "base":{"ref":"main","repo":{"full_name":"jlnieto/atenea"}},
                      "head":{"ref":"atenea/change-8bf60472-3c0e-49aa-99bf-6dc3c7e60eaf","sha":"3333333333333333333333333333333333333333","repo":{"full_name":"jlnieto/atenea"}}
                    }]
                    """.getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            GitHubProperties properties = new GitHubProperties();
            properties.setApiBaseUrl(new java.net.URI(
                    "http://127.0.0.1:" + server.getAddress().getPort()));
            properties.setToken("synthetic-token");
            String head = "atenea/change-8bf60472-3c0e-49aa-99bf-6dc3c7e60eaf";

            List<GitHubPullRequest> matches = new GitHubClient(
                    new ObjectMapper(), properties).findOpenPullRequests(
                    new GitHubRepositoryRef("jlnieto", "atenea"), head, "main");

            assertThat(matches).hasSize(1);
            assertThat(matches.getFirst().draft()).isTrue();
            assertThat(query.get()).contains(
                    "state=open", "base=main", "head=jlnieto%3Aatenea%2Fchange-");
        } finally {
            server.stop(0);
        }
    }
}
