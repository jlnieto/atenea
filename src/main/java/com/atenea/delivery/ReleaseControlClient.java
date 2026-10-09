package com.atenea.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Fixed local transport. App and AgentRuns never receive root/SSH credentials. */
@Component
public class ReleaseControlClient {
    public static final String PROTOCOL = "atenea-release/v1";
    private static final UnixDomainSocketAddress SOCKET = UnixDomainSocketAddress.of(
            "/run/atenea/release-v1/control.sock");
    private static final Set<String> STATES = Set.of("PREPARING", "READY", "ACCEPTED", "APPLYING",
            "ROLLING_BACK", "SUCCEEDED", "ROLLED_BACK", "FAILED", "BLOCKED", "ROLLBACK_FAILED");
    private static final Set<String> FIELDS = Set.of("protocol", "planId", "target", "sourceCommit",
            "artifactSha256", "predecessorCommit", "planSha256", "state", "errorCode", "operationId",
            "versionCode", "versionName", "createdAt", "expiresAt", "finishedAt", "effectiveSourceCommit", "resultSha256");
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final UnixDomainSocketAddress socket;

    @Autowired
    public ReleaseControlClient(ObjectMapper mapper,
            @Value("${ATENEA_RELEASE_CONTROL_ENABLED:false}") boolean enabled) {
        this(mapper,enabled,SOCKET);
    }

    // Package-private isolated fixture seam, not a caller/configurable runtime path.
    ReleaseControlClient(ObjectMapper mapper, boolean enabled, UnixDomainSocketAddress socket) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.socket = socket;
    }

    public boolean enabled() { return enabled; }
    public JsonNode plan(UUID id, DeliveryTarget target, String source) {
        return exchange(Map.of("operation", "PLAN", "planId", id.toString(),
                "target", target.name(), "sourceCommit", source));
    }
    public JsonNode inspect(UUID id) {
        return exchange(Map.of("operation", "INSPECT", "planId", id.toString()));
    }
    public JsonNode observeApp() {
        return exchange(Map.of("operation", "OBSERVE_APP"));
    }

    /** Separate read-only protocol: never accepted as an execution receipt. */
    public static AppObservation verifyAppObservation(JsonNode value) {
        Set<String> fields = new java.util.HashSet<>();
        if (value != null) value.fieldNames().forEachRemaining(fields::add);
        long now = Instant.now().getEpochSecond();
        if (value == null || !value.isObject() || !fields.equals(Set.of("protocol", "target", "state",
                    "sourceCommit", "imageSha256", "healthy", "observedAt", "planId", "operationId", "receiptSha256", "finishedAt"))
                || !"atenea-app-observation/v1".equals(value.path("protocol").asText())
                || !"APP_PROD".equals(value.path("target").asText()) || !"OBSERVED".equals(value.path("state").asText())
                || !value.path("sourceCommit").isTextual() || !value.path("sourceCommit").asText().matches("[0-9a-f]{40}")
                || !value.path("imageSha256").isTextual() || !value.path("imageSha256").asText().matches("[0-9a-f]{64}")
                || !value.path("receiptSha256").isTextual() || !value.path("receiptSha256").asText().matches("[0-9a-f]{64}")
                || !value.path("healthy").isBoolean()
                || !value.path("observedAt").isIntegralNumber() || !value.path("observedAt").canConvertToLong()
                || value.path("observedAt").asLong() < now - 60 || value.path("observedAt").asLong() > now + 5
                || !value.path("finishedAt").isIntegralNumber() || !value.path("finishedAt").canConvertToLong()
                || value.path("finishedAt").asLong() <= 0 || value.path("finishedAt").asLong() > value.path("observedAt").asLong()
                || !canonicalUuid(value.path("planId")) || !canonicalUuid(value.path("operationId"))) {
            throw new DeliveryRejectedException("APP_OBSERVATION_EVIDENCE_MISMATCH");
        }
        return new AppObservation(value.path("sourceCommit").asText(), value.path("healthy").asBoolean(),
                value.path("observedAt").asLong(), UUID.fromString(value.path("planId").asText()),
                UUID.fromString(value.path("operationId").asText()), value.path("receiptSha256").asText());
    }
    private static boolean canonicalUuid(JsonNode value) {
        try { return value.isTextual() && UUID.fromString(value.textValue()).toString().equals(value.textValue()); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    public record AppObservation(String sourceCommit, boolean healthy, long observedAt,
            UUID planId, UUID operationId, String receiptSha256) { }
    public JsonNode execute(UUID id, String fingerprint, UUID operationId) {
        return exchange(Map.of("operation", "EXECUTE", "planId", id.toString(),
                "planSha256", fingerprint, "operationId", operationId.toString()));
    }

    protected JsonNode exchange(Map<String, String> request) {
        if (!enabled) throw new DeliveryRejectedException("RELEASE_CONTROL_DISABLED");
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
             Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            if (!channel.connect(socket)) {
                channel.register(selector, SelectionKey.OP_CONNECT);
                while (!channel.finishConnect()) await(selector, deadline);
            }
            ByteBuffer outgoing = ByteBuffer.wrap((mapper.writeValueAsString(request) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            channel.register(selector, SelectionKey.OP_WRITE);
            while (outgoing.hasRemaining()) {
                if (channel.write(outgoing) == 0) await(selector, deadline);
            }
            channel.register(selector, SelectionKey.OP_READ);
            ByteArrayOutputStream incoming = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            while (incoming.size() <= 65536) {
                int count = channel.read(buffer);
                if (count < 0) throw new DeliveryRejectedException("RELEASE_TRANSPORT_UNAVAILABLE");
                if (count == 0) { await(selector, deadline); continue; }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    byte value = buffer.get();
                    if (value == '\n') {
                        JsonNode envelope = mapper.readTree(incoming.toByteArray());
                        if (!envelope.path("ok").asBoolean()) {
                            String code = envelope.path("errorCode").asText();
                            throw new DeliveryRejectedException(code.matches("[A-Z][A-Z0-9_]{2,79}") ? code : "EXECUTOR_REJECTED");
                        }
                        return envelope.path("result");
                    }
                    incoming.write(value);
                }
                buffer.clear();
            }
            throw new DeliveryRejectedException("RELEASE_RESPONSE_INVALID");
        } catch (DeliveryRejectedException exception) {
            throw exception;
        } catch (Exception exception) {
            // Never expose local paths, exception text, credentials or upstream logs.
            throw new DeliveryRejectedException("RELEASE_TRANSPORT_UNAVAILABLE");
        }
    }

    private static void await(Selector selector, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new DeliveryRejectedException("RELEASE_TRANSPORT_UNAVAILABLE");
        selector.select(Math.max(1, Duration.ofNanos(remaining).toMillis()));
        selector.selectedKeys().clear();
    }

    public static void verify(JsonNode value, UUID id, DeliveryTarget target, String source,
            String planSha, UUID executionId, boolean accepted) {
        Set<String> fields = new java.util.HashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        String state = value.path("state").asText();
        if (!value.isObject() || !FIELDS.equals(fields)
                || !PROTOCOL.equals(value.path("protocol").asText())
                || !id.toString().equals(value.path("planId").asText())
                || !target.name().equals(value.path("target").asText())
                || !source.equals(value.path("sourceCommit").asText())
                || !STATES.contains(state)
                || !nullableText(value.path("predecessorCommit"), "[0-9a-f]{40}")
                || !nullableText(value.path("effectiveSourceCommit"), "[0-9a-f]{40}")
                || !nullableText(value.path("artifactSha256"), "[0-9a-f]{64}")
                || !nullableText(value.path("planSha256"), "[0-9a-f]{64}")
                || !nullableText(value.path("resultSha256"), "[0-9a-f]{64}")
                || !nullableText(value.path("errorCode"), "[A-Z][A-Z0-9_]{2,79}")
                || !value.path("createdAt").isIntegralNumber() || !value.path("createdAt").canConvertToLong()
                || value.path("createdAt").asLong() <= 0
                || !nullableTime(value.path("expiresAt")) || !nullableTime(value.path("finishedAt"))
                || (DeliveryOperation.TERMINAL.contains(state) && value.path("finishedAt").isNull())
                || (!accepted && !Set.of("PREPARING", "READY", "FAILED", "BLOCKED").contains(state))
                || (accepted && Set.of("PREPARING", "READY").contains(state))
                || (!value.path("operationId").isNull() && !executionId.toString().equals(value.path("operationId").textValue()))
                || ("SUCCEEDED".equals(state) && !source.equals(value.path("effectiveSourceCommit").asText()))
                || ("SUCCEEDED".equals(state) && target == DeliveryTarget.ANDROID_STABLE && value.path("resultSha256").isNull())
                || (target != DeliveryTarget.ANDROID_STABLE && (!value.path("versionCode").isNull() || !value.path("versionName").isNull()))
                || (target == DeliveryTarget.ANDROID_STABLE && (state.equals("READY") || state.equals("SUCCEEDED"))
                    && (!value.path("versionCode").isIntegralNumber() || !value.path("versionCode").canConvertToLong()
                        || value.path("versionCode").asLong() <= 0 || !value.path("versionName").isTextual()
                        || !value.path("versionName").asText().matches("[0-9]+\\.[0-9]+\\.[0-9]+")))
                || (planSha != null && !planSha.equals(value.path("planSha256").asText()))
                || (accepted && !executionId.toString().equals(value.path("operationId").asText()))
                || ("READY".equals(value.path("state").asText())
                    && (!value.path("planSha256").asText().matches("[0-9a-f]{64}")
                        || !value.path("artifactSha256").asText().matches("[0-9a-f]{64}")
                        || !value.path("expiresAt").isIntegralNumber()))) {
            throw new DeliveryRejectedException("RELEASE_EVIDENCE_MISMATCH");
        }
    }

    private static boolean nullableText(JsonNode value, String pattern) {
        return value.isNull() || (value.isTextual() && value.asText().matches(pattern));
    }
    private static boolean nullableTime(JsonNode value) {
        return value.isNull() || (value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0);
    }
}
