package com.atenea.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Server-owned ancestry evidence, retained separately from executor receipt fields. */
public record ReleaseRecoverySource(String protocol, UUID integrationOperationId,
        String publishedHeadCommit, String integratedMergeCommit, String selectedMainCommit) {
    public static final String PROTOCOL = "integrated-release-recovery/v1";
    static final String FIELD = "releaseRecovery";
    private static final Set<String> FIELDS = Set.of("protocol", "integrationOperationId",
            "publishedHeadCommit", "integratedMergeCommit", "selectedMainCommit");

    public ReleaseRecoverySource {
        if (!PROTOCOL.equals(protocol) || integrationOperationId == null
                || publishedHeadCommit == null || !publishedHeadCommit.matches("[0-9a-f]{40}")
                || integratedMergeCommit == null || !integratedMergeCommit.matches("[0-9a-f]{40}")
                || selectedMainCommit == null || !selectedMainCommit.matches("[0-9a-f]{40}")) {
            throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
        }
    }

    public static ReleaseRecoverySource create(DeliveryOperation integrated, String selected) {
        return new ReleaseRecoverySource(PROTOCOL, integrated.id(), integrated.sourceCommit(),
                integrated.evidence().path("mergeCommit").asText(), selected);
    }

    public JsonNode json(ObjectMapper mapper) { return mapper.valueToTree(this); }

    public static ReleaseRecoverySource from(DeliveryOperation operation) {
        if (!operation.evidence().has(FIELD)) return null;
        JsonNode value = operation.evidence().path(FIELD);
        Set<String> fields = new HashSet<>(); value.fieldNames().forEachRemaining(fields::add);
        if (!value.isObject() || !FIELDS.equals(fields) || !PROTOCOL.equals(value.path("protocol").asText())
                || !"RELEASE".equals(operation.kind()) || operation.target() != DeliveryTarget.APP_PROD
                || !sha(value.path("publishedHeadCommit")) || !sha(value.path("integratedMergeCommit"))
                || !sha(value.path("selectedMainCommit"))
                || !value.path("selectedMainCommit").asText().equals(operation.sourceCommit())) {
            throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
        }
        try {
            UUID id = UUID.fromString(value.path("integrationOperationId").asText());
            if (!id.toString().equals(value.path("integrationOperationId").asText())) throw new IllegalArgumentException();
            return new ReleaseRecoverySource(PROTOCOL, id, value.path("publishedHeadCommit").asText(),
                    value.path("integratedMergeCommit").asText(), value.path("selectedMainCommit").asText());
        } catch (IllegalArgumentException rejected) {
            throw new DeliveryRejectedException("RELEASE_SOURCE_EVIDENCE_MISMATCH");
        }
    }

    private static boolean sha(JsonNode value) { return value.isTextual() && value.asText().matches("[0-9a-f]{40}"); }
}
