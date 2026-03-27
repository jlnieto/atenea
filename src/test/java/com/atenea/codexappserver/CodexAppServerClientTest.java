package com.atenea.codexappserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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

    private static final String COMPLETE_FINAL_ANSWER = "Implemented from fake connector.\n\nWith structured markdown.";
    private static final String RECOVERED_FINAL_ANSWER = "## Punto actual\n\nRecovered final answer.";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executeCreatesThreadWhenThreadIdIsMissing() throws Exception {
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-new",
                "turn-1",
                true,
                false,
                false,
                false,
                false,
                false
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
        assertEquals(COMPLETE_FINAL_ANSWER, result.finalAnswer());
        assertEquals("Implemented from fake connector. With structured markdown.", result.outputSummary());
        assertEquals(List.of("thread-new"), listenerProbe.threadIds);
        assertEquals(List.of("thread-new:turn-1"), listenerProbe.turnEvents);
    }

    @Test
    void executeReusesExistingThreadWithoutCreatingNewOne() throws Exception {
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-existing",
                "turn-2",
                true,
                false,
                false,
                false,
                false,
                false
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
        assertEquals(COMPLETE_FINAL_ANSWER, result.finalAnswer());
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
                false,
                false,
                false,
                false,
                false,
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

    @Test
    void startExecutionDoesNotLeaveCompletionFutureHungWhenSendCloseFails() throws Exception {
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-new",
                "turn-1",
                false,
                true,
                false,
                false,
                false,
                false
        );
        CodexAppServerClient client = new CodexAppServerClient(objectMapper, properties(), connector);

        CodexAppServerClient.CodexAppServerExecutionHandle handle = client.startExecution(
                new CodexAppServerExecutionRequest("/workspace/repos/internal/atenea", "inspect repo", null),
                CodexAppServerExecutionListener.NO_OP
        );
        connector.pushCloseBeforeCompletion();

        CodexAppServerExecutionResult result = handle.completionFuture().get(1, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(CodexAppServerExecutionResult.Status.FAILED, result.status());
        assertTrue(handle.completionFuture().isDone());
    }

    @Test
    void startExecutionDoesNotDegradeSuccessfulCompletionWhenSendCloseFails() throws Exception {
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-new",
                "turn-1",
                true,
                true,
                false,
                false,
                false,
                false
        );
        CodexAppServerClient client = new CodexAppServerClient(objectMapper, properties(), connector);

        CodexAppServerClient.CodexAppServerExecutionHandle handle = client.startExecution(
                new CodexAppServerExecutionRequest("/workspace/repos/internal/atenea", "inspect repo", null),
                CodexAppServerExecutionListener.NO_OP
        );

        CodexAppServerExecutionResult result = handle.completionFuture().get(1, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(CodexAppServerExecutionResult.Status.COMPLETED, result.status());
        assertEquals(COMPLETE_FINAL_ANSWER, result.finalAnswer());
    }

    @Test
    void startExecutionFailsWhenThreadReturnsToIdleWithoutTurnCompleted() throws Exception {
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-existing",
                "turn-3",
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false
        );
        CodexAppServerClient client = new CodexAppServerClient(objectMapper, properties(), connector);

        CodexAppServerClient.CodexAppServerExecutionHandle handle = client.startExecution(
                new CodexAppServerExecutionRequest("/workspace/repos/internal/atenea", "continue work", "thread-existing"),
                CodexAppServerExecutionListener.NO_OP
        );

        CodexAppServerExecutionResult result = handle.completionFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(CodexAppServerExecutionResult.Status.FAILED, result.status());
        assertEquals("Codex App Server thread returned to idle before turn completion", result.errorMessage());
    }

    @Test
    void startExecutionRecoversCompletedTurnViaThreadReadAfterIdleWithoutCompletion() throws Exception {
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-existing",
                "turn-3",
                false,
                false,
                true,
                false,
                false,
                false,
                true,
                false
        );
        CodexAppServerClient client = new CodexAppServerClient(objectMapper, properties(), connector);

        CodexAppServerClient.CodexAppServerExecutionHandle handle = client.startExecution(
                new CodexAppServerExecutionRequest("/workspace/repos/internal/atenea", "continue work", "thread-existing"),
                CodexAppServerExecutionListener.NO_OP
        );

        CodexAppServerExecutionResult result = handle.completionFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(CodexAppServerExecutionResult.Status.COMPLETED, result.status());
        assertEquals(RECOVERED_FINAL_ANSWER, result.finalAnswer());
        assertEquals("## Punto actual Recovered final answer.", result.outputSummary());
        assertTrue(connector.outboundMethods().contains("thread/read"));
    }

    @Test
    void startExecutionStillFailsWhenThreadReadRecoveryReturnsNonTerminalTurn() throws Exception {
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-existing",
                "turn-3",
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true
        );
        CodexAppServerClient client = new CodexAppServerClient(objectMapper, properties(), connector);

        CodexAppServerClient.CodexAppServerExecutionHandle handle = client.startExecution(
                new CodexAppServerExecutionRequest("/workspace/repos/internal/atenea", "continue work", "thread-existing"),
                CodexAppServerExecutionListener.NO_OP
        );

        CodexAppServerExecutionResult result = handle.completionFuture().get(3, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(CodexAppServerExecutionResult.Status.FAILED, result.status());
        assertEquals("Codex App Server thread returned to idle before turn completion", result.errorMessage());
    }

    @Test
    void startExecutionFailsWhenRealtimeTransportErrorsBeforeTurnCompleted() throws Exception {
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-existing",
                "turn-4",
                false,
                false,
                false,
                true,
                false,
                false
        );
        CodexAppServerClient client = new CodexAppServerClient(objectMapper, properties(), connector);

        CodexAppServerClient.CodexAppServerExecutionHandle handle = client.startExecution(
                new CodexAppServerExecutionRequest("/workspace/repos/internal/atenea", "continue work", "thread-existing"),
                CodexAppServerExecutionListener.NO_OP
        );

        CodexAppServerExecutionResult result = handle.completionFuture().get(1, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(CodexAppServerExecutionResult.Status.FAILED, result.status());
        assertEquals("Realtime transport exploded", result.errorMessage());
    }

    @Test
    void executeIgnoresRetryableErrorNotificationAndCompletesTurn() throws Exception {
        RecordingConnector connector = new RecordingConnector(
                objectMapper,
                "thread-existing",
                "turn-5",
                true,
                false,
                false,
                false,
                false,
                true,
                false,
                false
        );
        CodexAppServerClient client = new CodexAppServerClient(objectMapper, properties(), connector);

        CodexAppServerExecutionResult result = client.execute(
                new CodexAppServerExecutionRequest("/workspace/repos/internal/atenea", "continue work", "thread-existing"),
                CodexAppServerExecutionListener.NO_OP
        );

        assertEquals(CodexAppServerExecutionResult.Status.COMPLETED, result.status());
        assertEquals(COMPLETE_FINAL_ANSWER, result.finalAnswer());
    }

    private CodexAppServerProperties properties() {
        CodexAppServerProperties properties = new CodexAppServerProperties();
        properties.setUrl(URI.create("ws://fake"));
        properties.setConnectTimeout(Duration.ofMillis(100));
        properties.setStartTimeout(Duration.ofMillis(100));
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
        private final boolean failOnClose;
        private final boolean idleWithoutCompletion;
        private final boolean realtimeErrorBeforeCompletion;
        private final boolean realtimeClosedBeforeCompletion;
        private final boolean retryableErrorBeforeCompletion;
        private final boolean recoverViaThreadRead;
        private final boolean threadReadReturnsNonTerminalTurn;
        private final List<String> outboundMethods = new CopyOnWriteArrayList<>();
        private FakeWebSocket webSocket;

        private RecordingConnector(
                ObjectMapper objectMapper,
                String threadId,
                String turnId,
                boolean completeTurn,
                boolean failOnClose,
                boolean idleWithoutCompletion,
                boolean realtimeErrorBeforeCompletion,
                boolean realtimeClosedBeforeCompletion,
                boolean retryableErrorBeforeCompletion,
                boolean recoverViaThreadRead,
                boolean threadReadReturnsNonTerminalTurn
        ) {
            this.objectMapper = objectMapper;
            this.threadId = threadId;
            this.turnId = turnId;
            this.completeTurn = completeTurn;
            this.failOnClose = failOnClose;
            this.idleWithoutCompletion = idleWithoutCompletion;
            this.realtimeErrorBeforeCompletion = realtimeErrorBeforeCompletion;
            this.realtimeClosedBeforeCompletion = realtimeClosedBeforeCompletion;
            this.retryableErrorBeforeCompletion = retryableErrorBeforeCompletion;
            this.recoverViaThreadRead = recoverViaThreadRead;
            this.threadReadReturnsNonTerminalTurn = threadReadReturnsNonTerminalTurn;
        }

        private RecordingConnector(
                ObjectMapper objectMapper,
                String threadId,
                String turnId,
                boolean completeTurn,
                boolean failOnClose,
                boolean idleWithoutCompletion,
                boolean realtimeErrorBeforeCompletion,
                boolean realtimeClosedBeforeCompletion,
                boolean retryableErrorBeforeCompletion
        ) {
            this(
                    objectMapper,
                    threadId,
                    turnId,
                    completeTurn,
                    failOnClose,
                    idleWithoutCompletion,
                    realtimeErrorBeforeCompletion,
                    realtimeClosedBeforeCompletion,
                    retryableErrorBeforeCompletion,
                    false,
                    false
            );
        }

        @Override
        public WebSocket connect(CodexAppServerProperties properties, WebSocket.Listener listener) {
            webSocket = new FakeWebSocket(
                    objectMapper,
                    listener,
                    threadId,
                    turnId,
                    completeTurn,
                    failOnClose,
                    idleWithoutCompletion,
                    realtimeErrorBeforeCompletion,
                    realtimeClosedBeforeCompletion,
                    retryableErrorBeforeCompletion,
                    recoverViaThreadRead,
                    threadReadReturnsNonTerminalTurn,
                    outboundMethods);
            listener.onOpen(webSocket);
            return webSocket;
        }

        private List<String> outboundMethods() {
            return outboundMethods;
        }

        private void pushCloseBeforeCompletion() {
            webSocket.pushClose(1011, "transport failed");
        }
    }

    private static final class FakeWebSocket implements WebSocket {

        private final ObjectMapper objectMapper;
        private final WebSocket.Listener listener;
        private final String threadId;
        private final String turnId;
        private final boolean completeTurn;
        private final boolean failOnClose;
        private final boolean idleWithoutCompletion;
        private final boolean realtimeErrorBeforeCompletion;
        private final boolean realtimeClosedBeforeCompletion;
        private final boolean retryableErrorBeforeCompletion;
        private final boolean recoverViaThreadRead;
        private final boolean threadReadReturnsNonTerminalTurn;
        private final List<String> outboundMethods;

        private FakeWebSocket(
                ObjectMapper objectMapper,
                WebSocket.Listener listener,
                String threadId,
                String turnId,
                boolean completeTurn,
                boolean failOnClose,
                boolean idleWithoutCompletion,
                boolean realtimeErrorBeforeCompletion,
                boolean realtimeClosedBeforeCompletion,
                boolean retryableErrorBeforeCompletion,
                boolean recoverViaThreadRead,
                boolean threadReadReturnsNonTerminalTurn,
                List<String> outboundMethods
        ) {
            this.objectMapper = objectMapper;
            this.listener = listener;
            this.threadId = threadId;
            this.turnId = turnId;
            this.completeTurn = completeTurn;
            this.failOnClose = failOnClose;
            this.idleWithoutCompletion = idleWithoutCompletion;
            this.realtimeErrorBeforeCompletion = realtimeErrorBeforeCompletion;
            this.realtimeClosedBeforeCompletion = realtimeClosedBeforeCompletion;
            this.retryableErrorBeforeCompletion = retryableErrorBeforeCompletion;
            this.recoverViaThreadRead = recoverViaThreadRead;
            this.threadReadReturnsNonTerminalTurn = threadReadReturnsNonTerminalTurn;
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
                        if (retryableErrorBeforeCompletion) {
                            push("""
                                    {"method":"error","params":{"threadId":"%s","turnId":"%s","willRetry":true,"error":{"message":"Temporary upstream issue"}}}
                                    """.formatted(threadId, turnId));
                        }
                        if (realtimeErrorBeforeCompletion) {
                            push("""
                                    {"method":"thread/realtime/error","params":{"threadId":"%s","message":"Realtime transport exploded"}}
                                    """.formatted(threadId));
                        } else if (realtimeClosedBeforeCompletion) {
                            push("""
                                    {"method":"thread/realtime/closed","params":{"threadId":"%s","reason":"Realtime transport closed"}}
                                    """.formatted(threadId));
                        } else if (completeTurn) {
                            push("""
                                    {"method":"thread/status/changed","params":{"threadId":"%s","status":{"type":"idle"}}}
                                    """.formatted(threadId));
                            push("""
                                    {"method":"item/started","params":{"item":{"type":"agentMessage","id":"item-1","phase":"final_answer"}}}
                                    """);
                            push("""
                                    {"method":"item/agentMessage/delta","params":{"itemId":"item-1","delta":"Implemented from fake connector.\\n\\nWith structured markdown."}}
                                    """);
                            push("""
                                    {"method":"turn/completed","params":{"turn":{"id":"%s","status":"completed"}}}
                                    """.formatted(turnId));
                        } else if (idleWithoutCompletion) {
                            push("""
                                    {"method":"thread/status/changed","params":{"threadId":"%s","status":{"type":"idle"}}}
                                    """.formatted(threadId));
                        }
                    }
                    case "thread/read" -> {
                        if (recoverViaThreadRead) {
                            push("""
                                    {"id":"thread-read-%s","result":{"thread":{"id":"%s","turns":[{"id":"%s","status":"completed","items":[{"type":"agentMessage","id":"item-r1","phase":"final_answer","text":"## Punto actual\\n\\nRecovered final answer."}],"error":null}]}}}
                                    """.formatted(turnId, threadId, turnId));
                        } else if (threadReadReturnsNonTerminalTurn) {
                            push("""
                                    {"id":"thread-read-%s","result":{"thread":{"id":"%s","turns":[{"id":"%s","status":"inProgress","items":[],"error":null}]}}}
                                    """.formatted(turnId, threadId, turnId));
                        } else {
                            push("""
                                    {"id":"thread-read-%s","result":{"thread":{"id":"%s","turns":[]}}}
                                    """.formatted(turnId, threadId));
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

        private void pushClose(int statusCode, String reason) {
            listener.onClose(this, statusCode, reason);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            if (failOnClose) {
                return CompletableFuture.failedFuture(new IOException("Output closed"));
            }
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
