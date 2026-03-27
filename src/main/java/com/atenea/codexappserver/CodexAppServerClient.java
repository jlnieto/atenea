package com.atenea.codexappserver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodexAppServerClient {

    private static final Logger log = LoggerFactory.getLogger(CodexAppServerClient.class);
    private static final ExecutorService COMPLETION_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "codex-app-server-completion");
        thread.setDaemon(true);
        return thread;
    });
    // Allow a small window for servers that emit thread idle and turn completed almost back-to-back.
    private static final long IDLE_WITHOUT_COMPLETION_GRACE_MILLIS = 1500L;
    private static final String IDLE_BEFORE_COMPLETION_ERROR =
            "Codex App Server thread returned to idle before turn completion";
    private static final String REALTIME_ERROR_BEFORE_COMPLETION_ERROR =
            "Codex App Server realtime transport failed before turn completion";
    private static final String REALTIME_CLOSED_BEFORE_COMPLETION_ERROR =
            "Codex App Server realtime transport closed before turn completion";

    private final ObjectMapper objectMapper;
    private final CodexAppServerProperties properties;
    private final WebSocketConnector webSocketConnector;

    public CodexAppServerClient(ObjectMapper objectMapper, CodexAppServerProperties properties) {
        this(objectMapper, properties, new DefaultWebSocketConnector());
    }

    CodexAppServerClient(
            ObjectMapper objectMapper,
            CodexAppServerProperties properties,
            WebSocketConnector webSocketConnector
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.webSocketConnector = webSocketConnector;
    }

    public CodexAppServerPocResult runProofOfConcept() throws Exception {
        CodexAppServerExecutionResult result = execute(new CodexAppServerExecutionRequest(
                properties.getCwd(),
                properties.getPrompt()));
        return new CodexAppServerPocResult(
                result.threadId(),
                result.turnId(),
                result.status(),
                result.finalAnswer(),
                result.commentaryPreview(),
                result.errorMessage());
    }

    public CodexAppServerExecutionResult execute(CodexAppServerExecutionRequest request) throws Exception {
        return execute(request, CodexAppServerExecutionListener.NO_OP);
    }

    public CodexAppServerExecutionResult execute(
            CodexAppServerExecutionRequest request,
            CodexAppServerExecutionListener listener
    ) throws Exception {
        SessionState state = new SessionState();
        WebSocket webSocket = webSocketConnector.connect(properties, new CodexAppServerListener(state));
        try {
            send(webSocket, initializeRequest());
            JsonNode initializeResponse = waitForResponse(state, "initialize", "init", properties.getStartTimeout());
            logInboundResponse("initialize", initializeResponse);

            send(webSocket, Map.of("method", "initialized"));

            if (hasText(request.threadId())) {
                state.threadId = request.threadId().trim();
            } else {
                send(webSocket, threadStartRequest(request.repoPath()));
                JsonNode threadStartResponse = waitForResponse(
                        state,
                        "thread/start",
                        "thread-start",
                        properties.getStartTimeout());
                state.threadId = firstNonBlank(
                        state.threadId,
                        textAt(threadStartResponse, "result", "thread", "id"));
                if (hasText(state.threadId)) {
                    listener.onThreadStarted(state.threadId);
                }
                logInboundResponse("thread/start", threadStartResponse);
            }

            send(webSocket, turnStartRequest(state.threadId, request.prompt()));
            JsonNode turnStartResponse = waitForResponse(
                    state,
                    "turn/start",
                    "turn-start",
                    properties.getStartTimeout());
            state.turnStarted = true;
            state.turnId = firstNonBlank(state.turnId, textAt(turnStartResponse, "result", "turn", "id"));
            if (hasText(state.threadId) && hasText(state.turnId)) {
                listener.onTurnStarted(state.threadId, state.turnId);
            }
            logInboundResponse("turn/start", turnStartResponse);

            waitForCompletion(state, listener, properties.getCompletionTimeout());
            return state.toExecutionResult();
        } finally {
            closeWebSocket(webSocket, state);
        }
    }

    public CodexAppServerExecutionHandle startExecution(
            CodexAppServerExecutionRequest request,
            CodexAppServerExecutionListener listener
    ) throws Exception {
        SessionState state = new SessionState();
        WebSocket webSocket = webSocketConnector.connect(properties, new CodexAppServerListener(state));

        try {
            send(webSocket, initializeRequest());
            JsonNode initializeResponse = waitForResponse(state, "initialize", "init", properties.getStartTimeout());
            logInboundResponse("initialize", initializeResponse);

            send(webSocket, Map.of("method", "initialized"));

            if (hasText(request.threadId())) {
                state.threadId = request.threadId().trim();
            } else {
                send(webSocket, threadStartRequest(request.repoPath()));
                JsonNode threadStartResponse = waitForResponse(
                        state,
                        "thread/start",
                        "thread-start",
                        properties.getStartTimeout());
                state.threadId = firstNonBlank(
                        state.threadId,
                        textAt(threadStartResponse, "result", "thread", "id"));
                if (hasText(state.threadId)) {
                    listener.onThreadStarted(state.threadId);
                }
                logInboundResponse("thread/start", threadStartResponse);
            }

            send(webSocket, turnStartRequest(state.threadId, request.prompt()));
            JsonNode turnStartResponse = waitForResponse(
                    state,
                    "turn/start",
                    "turn-start",
                    properties.getStartTimeout());
            state.turnStarted = true;
            state.turnId = firstNonBlank(state.turnId, textAt(turnStartResponse, "result", "turn", "id"));
            if (hasText(state.threadId) && hasText(state.turnId)) {
                listener.onTurnStarted(state.threadId, state.turnId);
            }
            logInboundResponse("turn/start", turnStartResponse);

            return new CodexAppServerExecutionHandle(
                    state.threadId,
                    state.turnId,
                    awaitCompletionAsync(state, webSocket, listener));
        } finally {
            if (!state.turnStarted) {
                closeWebSocket(webSocket, state);
            }
        }
    }

    private CompletableFuture<CodexAppServerExecutionResult> awaitCompletionAsync(
            SessionState state,
            WebSocket webSocket,
            CodexAppServerExecutionListener listener
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                waitForCompletion(state, listener);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                state.failureMessage = firstNonBlank(
                        state.failureMessage,
                        "Interrupted while waiting for Codex App Server completion");
            } catch (Exception exception) {
                state.failureMessage = firstNonBlank(
                        state.failureMessage,
                        exception.getMessage(),
                        "Codex App Server completion failed");
            } finally {
                closeWebSocket(webSocket, state);
            }
            return state.toExecutionResult();
        }, COMPLETION_EXECUTOR);
    }

    private void waitForCompletion(
            SessionState state,
            CodexAppServerExecutionListener listener,
            java.time.Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!state.completed && state.failureMessage == null) {
            updateFailureIfThreadReturnedToIdle(state);
            if (state.failureMessage != null) {
                break;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("Timed out waiting for Codex App Server completion");
            }

            JsonNode message = state.messages.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (message == null) {
                continue;
            }

            handleMessage(message, state, listener);
        }
    }

    private void waitForCompletion(SessionState state, CodexAppServerExecutionListener listener) throws Exception {
        while (!state.completed && state.failureMessage == null) {
            updateFailureIfThreadReturnedToIdle(state);
            if (state.failureMessage != null) {
                break;
            }
            JsonNode message = state.messages.poll(1, TimeUnit.SECONDS);
            if (message == null) {
                continue;
            }

            handleMessage(message, state, listener);
        }
    }

    private JsonNode waitForResponse(
            SessionState state,
            String expectedMethod,
            String expectedId,
            java.time.Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IllegalStateException("Timed out waiting for " + expectedMethod + " response");
            }

            JsonNode message = state.messages.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (message == null) {
                continue;
            }

            if (message.hasNonNull("id") && expectedId.equals(message.get("id").asText())) {
                if (message.has("error")) {
                    String messageText = textAt(message, "error", "message");
                    throw new IllegalStateException("Codex App Server returned an error for "
                            + expectedMethod + ": " + firstNonBlank(messageText, preview(message)));
                }
                return message;
            }

            handleMessage(message, state, CodexAppServerExecutionListener.NO_OP);
        }
    }

    private void handleMessage(JsonNode message, SessionState state, CodexAppServerExecutionListener listener) {
        if (message.hasNonNull("id")) {
            logInboundResponse("unexpected-response", message);
            return;
        }

        String method = textAt(message, "method");
        if (method == null) {
            log.info("codex-app-server inbound type=unknown payload={}", preview(message));
            return;
        }

        switch (method) {
            case "thread/started" -> {
                state.idleWithoutCompletionObservedAtNanos = null;
                state.threadId = firstNonBlank(state.threadId, textAt(message, "params", "thread", "id"));
                if (hasText(state.threadId)) {
                    listener.onThreadStarted(state.threadId);
                }
                log.info("codex-app-server inbound method=thread/started threadId={}", state.threadId);
            }
            case "turn/started" -> {
                state.idleWithoutCompletionObservedAtNanos = null;
                state.turnStarted = true;
                state.turnId = firstNonBlank(state.turnId, textAt(message, "params", "turn", "id"));
                if (hasText(state.threadId) && hasText(state.turnId)) {
                    listener.onTurnStarted(state.threadId, state.turnId);
                }
                log.info("codex-app-server inbound method=turn/started threadId={} turnId={}",
                        state.threadId, state.turnId);
            }
            case "thread/status/changed" -> {
                String statusType = textAt(message, "params", "status", "type");
                if ("idle".equals(statusType) && state.turnStarted && !state.completed) {
                    state.idleWithoutCompletionObservedAtNanos = System.nanoTime();
                }
                log.info("codex-app-server inbound method=thread/status/changed payload={}", preview(message));
            }
            case "item/started" -> {
                String itemType = textAt(message, "params", "item", "type");
                String itemId = textAt(message, "params", "item", "id");
                String itemPhase = textAt(message, "params", "item", "phase");
                if ("agentMessage".equals(itemType) && itemId != null) {
                    state.agentMessagePhaseByItemId.put(itemId, itemPhase);
                }
                log.info("codex-app-server inbound method=item/started itemType={} phase={}", itemType, itemPhase);
            }
            case "item/agentMessage/delta" -> {
                String delta = textAt(message, "params", "delta");
                String itemId = textAt(message, "params", "itemId");
                String phase = itemId == null ? null : state.agentMessagePhaseByItemId.get(itemId);
                appendAgentMessageDelta(state, phase, delta);
                if (delta != null && !delta.isBlank()) {
                    log.info("codex-app-server inbound method=item/agentMessage/delta phase={} delta={}",
                            phase,
                            preview(delta));
                }
            }
            case "item/completed" -> log.info("codex-app-server inbound method=item/completed itemType={}",
                    textAt(message, "params", "item", "type"));
            case "turn/completed" -> {
                state.completed = true;
                state.idleWithoutCompletionObservedAtNanos = null;
                state.turnId = firstNonBlank(state.turnId, textAt(message, "params", "turn", "id"));
                state.completionStatus = textAt(message, "params", "turn", "status");
                state.failureMessage = textAt(message, "params", "turn", "error", "message");
                if (state.failureMessage == null && !"completed".equals(state.completionStatus)) {
                    state.failureMessage = "Codex App Server turn finished with status " + state.completionStatus;
                }
                log.info("codex-app-server inbound method=turn/completed threadId={} turnId={} status={} error={}",
                        state.threadId,
                        state.turnId,
                        firstNonBlank(state.completionStatus, "unknown"),
                        preview(state.failureMessage));
            }
            case "error" -> {
                boolean willRetry = message.path("params").path("willRetry").asBoolean(false);
                String errorMessage = firstNonBlank(
                        textAt(message, "params", "message"),
                        textAt(message, "params", "error", "message"),
                        "Codex App Server sent an error notification");
                if (willRetry) {
                    log.warn("codex-app-server inbound method=error willRetry=true message={}",
                            preview(errorMessage));
                } else {
                    state.failureMessage = errorMessage;
                    log.error("codex-app-server inbound method=error willRetry=false message={}",
                            preview(state.failureMessage));
                }
            }
            case "thread/realtime/error" -> {
                String errorMessage = firstNonBlank(
                        textAt(message, "params", "message"),
                        REALTIME_ERROR_BEFORE_COMPLETION_ERROR);
                if (!state.completed && state.failureMessage == null) {
                    state.failureMessage = errorMessage;
                }
                log.error("codex-app-server inbound method=thread/realtime/error threadId={} message={}",
                        textAt(message, "params", "threadId"),
                        preview(errorMessage));
            }
            case "thread/realtime/closed" -> {
                String reason = textAt(message, "params", "reason");
                if (!state.completed && state.failureMessage == null) {
                    state.failureMessage = firstNonBlank(reason, REALTIME_CLOSED_BEFORE_COMPLETION_ERROR);
                }
                log.warn("codex-app-server inbound method=thread/realtime/closed threadId={} reason={}",
                        textAt(message, "params", "threadId"),
                        preview(reason));
            }
            default -> log.info("codex-app-server inbound method={} payload={}", method, preview(message));
        }
    }

    private void updateFailureIfThreadReturnedToIdle(SessionState state) {
        if (state.completed || state.failureMessage != null || state.idleWithoutCompletionObservedAtNanos == null) {
            return;
        }
        long elapsedNanos = System.nanoTime() - state.idleWithoutCompletionObservedAtNanos;
        if (elapsedNanos < TimeUnit.MILLISECONDS.toNanos(IDLE_WITHOUT_COMPLETION_GRACE_MILLIS)) {
            return;
        }
        state.failureMessage = IDLE_BEFORE_COMPLETION_ERROR;
        log.warn(
                "codex-app-server detected idle thread without turn completion threadId={} turnId={} graceMs={}",
                state.threadId,
                state.turnId,
                IDLE_WITHOUT_COMPLETION_GRACE_MILLIS);
    }

    private void appendAgentMessageDelta(SessionState state, String phase, String delta) {
        if (delta == null || delta.isBlank()) {
            return;
        }

        if ("final_answer".equals(phase)) {
            state.finalAnswerText.append(delta);
            return;
        }

        if ("commentary".equals(phase)) {
            state.commentaryText.append(delta);
        }
    }

    private Map<String, Object> initializeRequest() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("clientInfo", Map.of(
                "name", "atenea",
                "version", "0.0.1-SNAPSHOT",
                "title", "Atenea Codex App Server PoC"));
        params.put("capabilities", Map.of());
        return request("init", "initialize", params);
    }

    private Map<String, Object> threadStartRequest(String repoPath) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfHasText(params, "cwd", repoPath);
        putIfHasText(params, "model", properties.getModel());
        return request("thread-start", "thread/start", params);
    }

    private Map<String, Object> turnStartRequest(String threadId, String prompt) {
        if (!hasText(threadId)) {
            throw new IllegalStateException("Thread id was not returned by Codex App Server");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("threadId", threadId);
        params.put("input", List.of(Map.of(
                "type", "text",
                "text", prompt)));
        return request("turn-start", "turn/start", params);
    }

    private Map<String, Object> request(String id, String method, Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params);
        return payload;
    }

    private void send(WebSocket webSocket, Object payload) throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(payload);
        log.info("codex-app-server outbound payload={}", preview(json));
        webSocket.sendText(json, true).join();
    }

    private void closeWebSocket(WebSocket webSocket, SessionState state) {
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } catch (Exception exception) {
            String closeMessage = firstNonBlank(exception.getMessage(), "Failed to close Codex App Server websocket");
            log.warn("codex-app-server websocket close failed message={}", preview(closeMessage), exception);
            if (!state.completed && state.failureMessage == null) {
                state.failureMessage = closeMessage;
            }
        }
    }

    private void logInboundResponse(String label, JsonNode response) {
        log.info("codex-app-server inbound response={} payload={}", label, preview(response));
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String textAt(JsonNode node, String... path) {
        JsonNode current = node;
        for (String element : path) {
            if (current == null) {
                return null;
            }
            current = current.get(element);
        }
        if (current == null || current.isNull()) {
            return null;
        }
        return current.isValueNode() ? current.asText() : current.toString();
    }

    private String preview(JsonNode node) {
        return preview(node == null ? null : node.toString());
    }

    private String preview(String value) {
        if (value == null) {
            return "null";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200) + " ...";
    }

    public record CodexAppServerPocResult(
            String threadId,
            String turnId,
            CodexAppServerExecutionResult.Status status,
            String finalAnswerPreview,
            String commentaryPreview,
            String errorMessage) {
    }

    private final class CodexAppServerListener implements WebSocket.Listener {

        private final SessionState state;
        private final StringBuilder partialMessage = new StringBuilder();

        private CodexAppServerListener(SessionState state) {
            this.state = state;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("codex-app-server connected url={}", properties.getUrl());
            webSocket.request(1);
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialMessage.append(data);
            if (last) {
                String payload = partialMessage.toString();
                partialMessage.setLength(0);
                try {
                    state.messages.put(objectMapper.readTree(payload));
                } catch (Exception exception) {
                    state.failureMessage = "Failed to parse Codex App Server message";
                    throw new IllegalStateException("Could not parse WebSocket payload: " + payload, exception);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            state.failureMessage = error.getMessage();
            state.messages.offer(NullNode.getInstance());
            log.error("codex-app-server websocket error", error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!state.completed && state.failureMessage == null) {
                state.failureMessage = "Codex App Server connection closed before turn completion";
            }
            state.messages.offer(NullNode.getInstance());
            log.warn("codex-app-server websocket closed status={} reason={}", statusCode, preview(reason));
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
    }

    public record CodexAppServerExecutionHandle(
            String threadId,
            String turnId,
            CompletableFuture<CodexAppServerExecutionResult> completionFuture) {
    }

    interface WebSocketConnector {
        WebSocket connect(CodexAppServerProperties properties, WebSocket.Listener listener);
    }

    private static final class DefaultWebSocketConnector implements WebSocketConnector {

        @Override
        public WebSocket connect(CodexAppServerProperties properties, WebSocket.Listener listener) {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(properties.getConnectTimeout())
                    .build();

            return client.newWebSocketBuilder()
                    .connectTimeout(properties.getConnectTimeout())
                    .buildAsync(properties.getUrl(), listener)
                    .join();
        }
    }

    private static final class SessionState {

        private final LinkedBlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final StringBuilder commentaryText = new StringBuilder();
        private final StringBuilder finalAnswerText = new StringBuilder();
        private final Map<String, String> agentMessagePhaseByItemId = new HashMap<>();
        private String threadId;
        private String turnId;
        private boolean turnStarted;
        private boolean completed;
        private String completionStatus;
        private String failureMessage;
        private Long idleWithoutCompletionObservedAtNanos;

        private CodexAppServerExecutionResult toExecutionResult() {
            return new CodexAppServerExecutionResult(
                    threadId,
                    turnId,
                    mapStatus(),
                    preview(finalAnswerText.toString()),
                    preview(commentaryText.toString()),
                    preview(failureMessage));
        }

        private CodexAppServerPocResult toResult() {
            CodexAppServerExecutionResult result = toExecutionResult();
            return new CodexAppServerPocResult(
                    result.threadId(),
                    result.turnId(),
                    result.status(),
                    result.finalAnswer(),
                    result.commentaryPreview(),
                    result.errorMessage());
        }

        private CodexAppServerExecutionResult.Status mapStatus() {
            if ("completed".equals(completionStatus)) {
                return CodexAppServerExecutionResult.Status.COMPLETED;
            }
            if ("interrupted".equals(completionStatus)) {
                return CodexAppServerExecutionResult.Status.INTERRUPTED;
            }
            return CodexAppServerExecutionResult.Status.FAILED;
        }

        private String preview(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.replaceAll("\\s+", " ").trim();
            if (normalized.isEmpty()) {
                return null;
            }
            if (normalized.length() <= 200) {
                return normalized;
            }
            return normalized.substring(0, 200) + " ...";
        }
    }
}
