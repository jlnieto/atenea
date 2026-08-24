package com.atenea.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubClientTest {

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
