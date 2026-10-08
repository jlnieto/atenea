package com.atenea.remoteworker;

import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class DevelopmentChangeSourceUpdateWorkerClient implements DevelopmentChangeSourceUpdateGateway,
        DevelopmentChangeSourceFinalizationGateway {
    private static final int MAX_RESPONSE = 262144;
    private static final Set<String> ERRORS = Set.of("SOURCE_UPDATE_REJECTED", "SOURCE_UPDATE_REF_MOVED",
            "SOURCE_UPDATE_DIRTY_WORKSPACE", "SOURCE_UPDATE_LATER_EDIT", "SOURCE_UPDATE_EXECUTION_ACTIVE",
            "SOURCE_UPDATE_IDENTITY_CONFLICT");
    private final RemoteWorkerProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public DevelopmentChangeSourceUpdateWorkerClient(RemoteWorkerProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        http = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
    }

    @Override
    public Preparation exchange(DevelopmentChangeSourceUpdateCommand command,
            DevelopmentChangeSourceUpdateCommand.Action action) {
        var body = request(command, action);
        return validate(send(body, command.owner().idempotencyKey(), "source-updates/" + action.path), mapper.valueToTree(body));
    }

    @Override
    public Result finalizeSource(DevelopmentChangeSourceFinalizationCommand command,
            DevelopmentChangeSourceFinalizationCommand.Action action) {
        var body = finalizationRequest(command, action);
        JsonNode response = send(body, command.owner().idempotencyKey(), "source-finalizations/" + action.name().toLowerCase(java.util.Locale.ROOT));
        return validateFinalization(response, mapper.valueToTree(body));
    }

    private JsonNode send(TreeMap<String, Object> body, java.util.UUID key, String path) {
        try {
            String token = Files.readString(Path.of(properties.getTokenFile())).trim();
            if (token.length() < 32) throw new IOException("Worker token unavailable");
            String endpoint = properties.getEndpoint().replaceAll("/+$", "");
            var request = HttpRequest.newBuilder(URI.create(endpoint
                            + "/v1/development-changes/" + path))
                    .timeout(properties.getWorkspaceProvisionTimeout())
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", key.toString())
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
            long started = System.nanoTime();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                byte[] bytes = boundedResponse(stream, started);
                if (bytes.length > MAX_RESPONSE) throw protocol();
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    // Never expose remote message text, paths, values, or unreviewed error codes.
                    String code = "SOURCE_UPDATE_RESPONSE_UNCERTAIN";
                    try {
                        String candidate = mapper.readTree(bytes).path("code").asText();
                        if (ERRORS.contains(candidate)) code = candidate;
                    } catch (IOException ignored) { }
                    boolean uncertain = code.equals("SOURCE_UPDATE_RESPONSE_UNCERTAIN");
                    throw new RemoteWorkerException("Pinned source update was not confirmed", response.statusCode(),
                            code, uncertain ? RemoteWorkerFailureCategory.TRANSPORT : RemoteWorkerFailureCategory.OWNERSHIP,
                            uncertain, AgentRunRecoveryNextAction.REQUEST_RECONCILIATION, null);
                }
                return mapper.readTree(bytes);
            }
        } catch (RemoteWorkerException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RemoteWorkerException("Pinned source update interrupted", failure);
        } catch (IOException failure) {
            throw new RemoteWorkerException("Pinned source update response unavailable", failure);
        } catch (RuntimeException failure) {
            throw protocol();
        }
    }

    TreeMap<String, Object> finalizationRequest(DevelopmentChangeSourceFinalizationCommand command,
            DevelopmentChangeSourceFinalizationCommand.Action action) {
        var owner = command.owner();
        var cleanOwner = new DevelopmentChangeBranchPublicationCommand(owner.operationId(), owner.idempotencyKey(),
                owner.changeKey(), owner.databaseProjectId(), owner.projectIdentity(), owner.repository(), owner.repositoryBranch(),
                owner.baseCommit(), owner.sourceCommit(), owner.workspaceBranch(), owner.workspaceIdentity(), owner.workerId(),
                owner.sourceRevision(), null);
        var body = request(new DevelopmentChangeSourceUpdateCommand(cleanOwner, command.targetMainCommit(),
                command.publicationReceiptSha256()), DevelopmentChangeSourceUpdateCommand.Action.INSPECT);
        body.remove("requestFingerprintSha256");
        body.put("protocolVersion", "development-change-source-finalization/v1");
        body.put("operation", action.name());
        body.put("effect", switch (action) {
            case FINALIZE -> "FINALIZE_VALIDATED_SOURCE";
            case INSPECT -> "OBSERVE_ONLY";
            case RECOVER -> "RESUME_PINNED_SOURCE";
        });
        body.put("sourceFingerprintSha256", owner.sourceFingerprintSha256());
        body.put("preparationOperationId", command.preparationOperationId().toString());
        body.put("preparationReceiptSha256", command.preparationReceiptSha256());
        body.put("validationProjectionSha256", command.validationProjectionSha256());
        try {
            body.put("requestFingerprintSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body))));
        } catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
        return body;
    }

    Result validateFinalization(JsonNode response, JsonNode request) {
        if (response == null || !response.isObject()) throw protocol();
        var expected = new HashSet<String>();
        request.fieldNames().forEachRemaining(key -> {
            expected.add(key);
            JsonNode actual = response.get(key), wanted = request.get(key);
            if (actual == null || !(wanted.isIntegralNumber() ? actual.isIntegralNumber()
                    && actual.longValue() == wanted.longValue() : wanted.equals(actual))) throw protocol();
        });
        expected.addAll(Set.of("state", "publishedHeadSha", "expectedTreeSha", "finalizationReceiptSha256", "valuesExposed"));
        var keys = new HashSet<String>(); response.fieldNames().forEachRemaining(keys::add);
        if (!expected.equals(keys) || !response.path("valuesExposed").isBoolean()
                || response.path("valuesExposed").booleanValue() || !response.path("state").isTextual()) throw protocol();
        FinalizationState state;
        try { state = FinalizationState.valueOf(response.path("state").textValue()); }
        catch (IllegalArgumentException invalid) { throw protocol(); }
        String head = nullableHash(response.get("publishedHeadSha"), "[0-9a-f]{40}|[0-9a-f]{64}");
        String tree = nullableHash(response.get("expectedTreeSha"), "[0-9a-f]{40}|[0-9a-f]{64}");
        String receipt = nullableHash(response.get("finalizationReceiptSha256"), "[0-9a-f]{64}");
        if (state == FinalizationState.ABSENT ? head != null || tree != null || receipt != null
                : head == null || tree == null) throw protocol();
        if (state == FinalizationState.PUBLISHED ? receipt == null : receipt != null) throw protocol();
        if (Set.of("FINALIZE","RECOVER").contains(request.path("operation").asText()) && state != FinalizationState.PUBLISHED) throw protocol();
        return new Result(state, head, tree, receipt);
    }

    private byte[] boundedResponse(InputStream stream, long started) throws IOException {
        // ofInputStream completes on headers; HttpRequest.timeout alone does not
        // bound a stalled body. Closing the subscription unblocks its reader.
        var watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "atenea-source-update-response-deadline");
            thread.setDaemon(true);
            return thread;
        });
        long remaining = Math.max(1, properties.getWorkspaceProvisionTimeout().toNanos()
                - (System.nanoTime() - started));
        var deadline = watchdog.schedule(() -> {
            try { stream.close(); } catch (IOException ignored) { }
        }, remaining, TimeUnit.NANOSECONDS);
        try { return stream.readNBytes(MAX_RESPONSE + 1); }
        finally { deadline.cancel(false); watchdog.shutdownNow(); }
    }

    TreeMap<String, Object> request(DevelopmentChangeSourceUpdateCommand command,
            DevelopmentChangeSourceUpdateCommand.Action action) {
        var owner = command.owner();
        var body = new TreeMap<String, Object>();
        body.put("schemaVersion", 1);
        body.put("protocolVersion", command.capability());
        body.put("effect", action.effect);
        body.put("operation", action.name());
        body.put("operationId", owner.operationId().toString());
        body.put("idempotencyKey", owner.idempotencyKey().toString());
        body.put("changeKey", owner.changeKey().toString());
        body.put("databaseProjectId", owner.databaseProjectId());
        body.put("projectId", owner.projectIdentity());
        body.put("repository", owner.repository());
        body.put("repositoryBranch", owner.repositoryBranch());
        body.put("baseCommit", owner.baseCommit());
        body.put("sourceCommit", owner.sourceCommit());
        body.put("targetMainCommit", command.targetMainCommit());
        body.put("workspaceBranch", owner.workspaceBranch());
        body.put("workspaceIdentity", owner.workspaceIdentity());
        body.put("workerId", owner.workerId());
        body.put("sourceRevision", owner.sourceRevision());
        body.put("sourceFingerprintSha256", owner.sourceFingerprintSha256());
        body.put("publicationReceiptSha256", command.publicationReceiptSha256());
        if (command.continuation()) {
            body.put("predecessorPreparationOperationId",command.predecessorPreparationOperationId().toString());
            body.put("predecessorPreparationReceiptSha256",command.predecessorPreparationReceiptSha256());
            body.put("publishedSourceRevision",command.publishedSourceRevision());
        }
        try {
            body.put("requestFingerprintSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(body))));
        } catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
        return body;
    }

    Preparation validate(JsonNode response, JsonNode request) {
        if (response == null || !response.isObject()) throw protocol();
        var expected = new HashSet<String>();
        request.fieldNames().forEachRemaining(key -> {
            expected.add(key);
            JsonNode actual = response.get(key), wanted = request.get(key);
            // Integral JSON types compare numerically, but strings/booleans/coercions do not.
            if (actual == null || !(wanted.isIntegralNumber()
                    ? actual.isIntegralNumber() && actual.longValue() == wanted.longValue()
                    : wanted.equals(actual))) throw protocol();
        });
        expected.addAll(Set.of("state", "preparedTreeSha", "conflictFiles", "preparedFingerprintSha256",
                "receiptSha256", "valuesExposed"));
        var actualKeys = new HashSet<String>();
        response.fieldNames().forEachRemaining(actualKeys::add);
        if (!expected.equals(actualKeys) || !response.path("valuesExposed").isBoolean()
                || response.path("valuesExposed").booleanValue() || !response.path("state").isTextual()) throw protocol();
        State state;
        try { state = State.valueOf(response.path("state").textValue()); }
        catch (IllegalArgumentException invalid) { throw protocol(); }
        if (request.path("operation").asText().equals("RECOVER") && !Set.of(State.NEEDS_RESOLUTION,State.READY_TO_FINALIZE).contains(state)) throw protocol();
        String tree = nullableHash(response.get("preparedTreeSha"), "[0-9a-f]{40}|[0-9a-f]{64}");
        String fingerprint = nullableHash(response.get("preparedFingerprintSha256"), "[0-9a-f]{64}");
        String receipt = nullableHash(response.get("receiptSha256"), "[0-9a-f]{64}");
        JsonNode files = response.get("conflictFiles");
        if (!files.isArray() || files.size() > 1000) throw protocol();
        List<String> conflicts = new ArrayList<>();
        for (JsonNode file : files) {
            if (!file.isTextual()) throw protocol();
            String name = file.textValue();
            if (name.isEmpty() || name.length() > 1024 || name.startsWith("/") || name.contains("\\")
                    || name.codePoints().anyMatch(c -> c < 32 || c == 127)
                    || List.of(name.split("/", -1)).stream().anyMatch(p -> p.equals("..") || p.equals(".") || p.isEmpty())
                    || conflicts.contains(name)) throw protocol();
            conflicts.add(name);
        }
        if (state == State.ABSENT ? tree != null || receipt != null || fingerprint != null || !conflicts.isEmpty()
                : tree == null || receipt == null) throw protocol();
        if (state == State.PREPARED && fingerprint != null
                || state == State.NEEDS_RESOLUTION && (fingerprint == null || conflicts.isEmpty())
                || state == State.READY_TO_FINALIZE && !conflicts.isEmpty()) throw protocol();
        return new Preparation(state, tree, conflicts, fingerprint, receipt);
    }

    private String nullableHash(JsonNode value, String pattern) {
        if (value == null) throw protocol();
        if (value.isNull()) return null;
        if (!value.isTextual() || !value.textValue().matches(pattern)) throw protocol();
        return value.textValue();
    }

    private RemoteWorkerException protocol() {
        return new RemoteWorkerException("Pinned source update violated the fixed contract", 502,
                "SOURCE_UPDATE_PROTOCOL_FAILURE", RemoteWorkerFailureCategory.PROTOCOL, false,
                AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR, null);
    }
}
