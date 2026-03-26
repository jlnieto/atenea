package com.atenea.codexappserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class CodexAppServerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executeCreatesThreadWhenThreadIdIsMissing() throws Exception {
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-new",
                "turn-1",
                true
        );
        ListenerProbe listenerProbe = new ListenerProbe();
        CodexAppServerClient client = new CodexAppServerClient(objectMapper, properties(), connector);

        CodexAppServerExecutionResult result = client.execute(
                new CodexAppServerExecutionRequest("/workspace/repos/internal/atenea", "inspect repo", null),
                listenerProbe
        );

        assertEquals(List.of("initialize", "initialized", "thread/start", "turn/start"), connector.outboundMethods());
        assertEquals("thread-new", result.threadId());
        assertEquals("turn-1", result.turnId());
        assertEquals(CodexAppServerExecutionResult.Status.COMPLETED, result.status());
        assertEquals("Implemented from fake connector.", result.finalAnswer());
        assertEquals(List.of("thread-new"), listenerProbe.threadIds);
        assertEquals(List.of("thread-new:turn-1"), listenerProbe.turnEvents);
    }

    @Test
    void executeReusesExistingThreadWithoutCreatingNewOne() throws Exception {
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-existing",
                "turn-2",
                true
        );
        ListenerProbe listenerProbe = new ListenerProbe();
        CodexAppServerClient client = new CodexAppServerClient(objectMapper, properties(), connector);

        CodexAppServerExecutionResult result = client.execute(
                new CodexAppServerExecutionRequest(
                        "/workspace/repos/internal/atenea",
                        "continue work",
                        "thread-existing"
                ),
                listenerProbe
        );

        assertEquals(List.of("initialize", "initialized", "turn/start"), connector.outboundMethods());
        assertEquals("thread-existing", result.threadId());
        assertEquals("turn-2", result.turnId());
        assertEquals(CodexAppServerExecutionResult.Status.COMPLETED, result.status());
        assertEquals("Implemented from fake connector.", result.finalAnswer());
        assertEquals(List.of(), listenerProbe.threadIds);
        assertEquals(List.of("thread-existing:turn-2"), listenerProbe.turnEvents);
    }

    @Test
    void executeTimesOutWhenCompletionNeverArrives() {
        CodexAppServerProperties properties = properties();
        properties.setCompletionTimeout(Duration.ofMillis(50));
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-new",
                "turn-timeout",
                false
        );
        CodexAppServerClient client = new CodexAppServerClient(objectMapper, properties, connector);

        TimeoutException exception = assertThrows(
                TimeoutException.class,
                () -> client.execute(new CodexAppServerExecutionRequest(
                        "/workspace/repos/internal/atenea",
                        "inspect repo",
                        null))
        );

        assertEquals("Timed out waiting for Codex App Server completion", exception.getMessage());
    }

    private CodexAppServerProperties properties() {
        CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setUrl(URI.create("ws://fake"));
        properties.setConnectTimeout(Duration.ofMillis(100));
        properties.setCompletionTimeout(Duration.ofSeconds(1));
        return properties;
    }

    private static final class ListenerProbe implements CodexAppServerExecutionListener {

        private final List<String> threadIds = new ArrayList<>();
        private final List<String> turnEvents = new ArrayList<>();

        @Override
        public void onThreadStarted(String threadId) {
            threadIds.add(threadId);
        }

        @Override
        public void onTurnStarted(String threadId, String turnId) {
            turnEvents.add(threadId + ":" + turnId);
        }
    }

    private static final class RecordingConnector implements CodexAppServerClient.WebSocketConnector {

        private final ObjectMapper objectMapper;
        private final String threadId;
        private final String turnId;
        private final boolean completeTurn;
        private final List<String> outboundMethods = new CopyOnWriteArrayList<>();

        private RecordingConnector(ObjectMapper objectMapper, String threadId, String turnId, boolean completeTurn) {
            this.objectMapper = objectMapper;
            this.threadId = threadId;
            this.turnId = turnId;
            this.completeTurn = completeTurn;
        }

        @Override
        public WebSocket connect(CodexAppServerProperties properties, WebSocket.Listener listener) {
            FakeWebSocket webSocket = new FakeWebSocket(objectMapper, listener, threadId, turnId, completeTurn, outboundMethods);
            listener.onOpen(webSocket);
            return webSocket;
        }

        private List<String> outboundMethods() {
            return outboundMethods;
        }
    }

    private static final class FakeWebSocket implements WebSocket {

        private final ObjectMapper objectMapper;
        private final WebSocket.Listener listener;
        private final String threadId;
        private final String turnId;
        private final boolean completeTurn;
        private final List<String> outboundMethods;

        private FakeWebSocket(
                ObjectMapper objectMapper,
                WebSocket.Listener listener,
                String threadId,
                String turnId,
                boolean completeTurn,
                List<String> outboundMethods
        ) {
            this.objectMapper = objectMapper;
            this.listener = listener;
            this.threadId = threadId;
            this.turnId = turnId;
            this.completeTurn = completeTurn;
            this.outboundMethods = outboundMethods;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            try {
                JsonNode payload = objectMapper.readTree(data.toString());
                String method = payload.path("method").asText(null);
                if (method != null) {
                    outboundMethods.add(method);
                }

                switch (method) {
                    case "initialize" -> push("""
                            {"id":"init","result":{"ok":true}}
                            """);
                    case "thread/start" -> push("""
                            {"id":"thread-start","result":{"thread":{"id":"%s"}}}
                            """.formatted(threadId));
                    case "turn/start" -> {
                        push("""
                                {"id":"turn-start","result":{"turn":{"id":"%s"}}}
                                """.formatted(turnId));
                        if (completeTurn) {
                            push("""
                                    {"method":"item/started","params":{"item":{"type":"agentMessage","id":"item-1","phase":"final_answer"}}}
                                    """);
                            push("""
                                    {"method":"item/agentMessage/delta","params":{"itemId":"item-1","delta":"Implemented from fake connector."}}
                                    """);
                            push("""
                                    {"method":"turn/completed","params":{"turn":{"id":"%s","status":"completed"}}}
                                    """.formatted(turnId));
                        }
                    }
                    case "initialized" -> {
                    }
                    default -> {
                    }
                }
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return CompletableFuture.completedFuture(this);
        }

        private void push(String json) throws Exception {
            listener.onText(this, json, true);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(java.nio.ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(java.nio.ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(java.nio.ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
    }
}
