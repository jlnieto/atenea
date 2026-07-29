package com.atenea.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
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
                    {"number":17,"html_url":"https://github.com/jlnieto/atenea/pull/17","state":"open","merged":false}
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
            assertThat(authorization.get()).isEqualTo("Bearer ephemeral-test-token");
            assertThat(properties.getToken()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
