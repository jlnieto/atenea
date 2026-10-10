package com.atenea.delivery;

import com.atenea.persistence.worksession.WorkSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/** Immutable server-owned identity. Runtime observations are retained separately. */
public record ChangeCompletionEvidence(String protocol, Long sessionId, Long projectId, UUID changeKey,
        UUID remoteSessionId, String workerId, String workloadKind, String workspaceIdentity, String workspaceBranch,
        String baseCommit, String canonicalCommit, long sourceRevision, String sourceFingerprintSha256,
        String validationProjectionSha256, String validationDefinitionRevision, String publishedHeadCommit,
        String publicationReceiptSha256, String pullRequestUrl, UUID integrationOperationId, String integratedMergeCommit) {
    public static final String FIELD = "completion";
    private static final String PROTOCOL = "delivered-change-completion/v1";

    static ChangeCompletionEvidence capture(WorkSessionEntity session, DeliveryOperation integration) {
        if (!"INTEGRATE".equals(integration.kind()) || !"SUCCEEDED".equals(integration.state())
                || integration.target() != DeliveryTarget.APP_PROD || !session.getId().equals(integration.sessionId())
                || !Objects.equals(session.getFinalCommitSha(), integration.sourceCommit())) reject();
        return snapshot(session, integration.id(), integration.evidence().path("mergeCommit").asText());
    }

    private static ChangeCompletionEvidence snapshot(WorkSessionEntity session, UUID integrationId, String merge) {
        var change = session.getDevelopmentChange();
        if (change == null || change.getProject() == null || session.getProject() == null
                || !Objects.equals(change.getProject().getId(), session.getProject().getId())
                || !Objects.equals(change.getChangeKey(), session.getPublishedChangeKey())
                || !Objects.equals(change.getSourceRevision(), session.getPublishedSourceRevision())
                || !Objects.equals(change.getSourceFingerprintSha256(), session.getPublishedSourceFingerprintSha256())
                || !Objects.equals(change.getSourceFingerprintSha256(), session.getSourceTreeFingerprintSha256())
                || !"jlnieto/atenea".equals(session.getPublishedRepository())
                || !"main".equals(session.getPublishedBaseBranch())
                || !Objects.equals(session.getWorkspaceBranch(), session.getPublishedHeadBranch())
                || !sha256(session.getPublicationReceiptSha256()) || !sha256(session.getValidationProjectionSha256())
                || !sha256(session.getSourceTreeFingerprintSha256()) || !sha(session.getFinalCommitSha()) || !sha(merge)
                || session.getRemoteSessionId() == null || session.getValidatedAt() == null
                || session.getIntegrationReadyAt() == null || session.getSourceTreeObservedAt() == null
                || session.getValidationDefinitionRevision() == null || session.getValidationDefinitionRevision().isBlank()
                || session.getPullRequestUrl() == null
                || !session.getPullRequestUrl().matches("https://github\\.com/jlnieto/atenea/pull/[1-9][0-9]*")) reject();
        return new ChangeCompletionEvidence(PROTOCOL, session.getId(), session.getProject().getId(), change.getChangeKey(),
                session.getRemoteSessionId(), session.getSelectedWorkerId(), session.getRemoteWorkloadKind(),
                session.getWorkspaceIdentity(), session.getWorkspaceBranch(), change.getBaseCommit(),
                change.getObservedCanonicalCommit(), change.getSourceRevision(), change.getSourceFingerprintSha256(),
                session.getValidationProjectionSha256(), session.getValidationDefinitionRevision(), session.getFinalCommitSha(),
                session.getPublicationReceiptSha256(), session.getPullRequestUrl(), integrationId, merge);
    }

    public static ChangeCompletionEvidence require(DeliveryOperation operation, WorkSessionEntity session, ObjectMapper mapper) {
        try {
            var value = operation.evidence().path(FIELD);
            ChangeCompletionEvidence binding = mapper.treeToValue(value, ChangeCompletionEvidence.class);
            if (!"FINALIZE".equals(operation.kind()) || operation.target() != DeliveryTarget.APP_PROD
                    || binding == null || !binding.equals(snapshot(session, binding.integrationOperationId(), binding.integratedMergeCommit()))
                    || binding.integrationOperationId() == null || !operation.sessionId().equals(session.getId())
                    || !Objects.equals(operation.sourceCommit(), binding.publishedHeadCommit())
                    || !Objects.equals(operation.evidence().path("completionBindingSha256").asText(), binding.sha256(mapper))
                    || !sameRecord(value, mapper.valueToTree(binding))) reject();
            return binding;
        } catch (DeliveryRejectedException rejected) { throw rejected; }
        catch (Exception invalid) { throw new DeliveryRejectedException("COMPLETION_EVIDENCE_MISMATCH"); }
    }

    ObjectNode initialEvidence(ObjectMapper mapper) {
        ObjectNode evidence = mapper.createObjectNode();
        evidence.set(FIELD, mapper.valueToTree(this));
        evidence.put("completionBindingSha256", sha256(mapper));
        return evidence;
    }

    String sha256(ObjectMapper mapper) {
        try {
            // Serialize the fixed record, not the differently ordered JSONB object.
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(this).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception invalid) { throw new DeliveryRejectedException("COMPLETION_EVIDENCE_MISMATCH"); }
    }
    private static boolean sameRecord(JsonNode observed, JsonNode expected) {
        // PostgreSQL JSONB/Jackson may read a small BIGINT as IntNode rather
        // than LongNode. Compare exact integral values, never coerce strings
        // or decimals, and still reject additional fields.
        var actualFields=new HashSet<String>(); observed.fieldNames().forEachRemaining(actualFields::add);
        var expectedFields=new HashSet<String>(); expected.fieldNames().forEachRemaining(expectedFields::add);
        if (!observed.isObject() || !actualFields.equals(expectedFields)) return false;
        for (String field:expectedFields) {
            JsonNode actual=observed.path(field), value=expected.path(field);
            if (value.isIntegralNumber()) {
                if (!actual.isIntegralNumber() || !actual.bigIntegerValue().equals(value.bigIntegerValue())) return false;
            } else if (!actual.equals(value)) return false;
        }
        return true;
    }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-f]{40}"); }
    private static boolean sha256(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static void reject() { throw new DeliveryRejectedException("COMPLETION_EVIDENCE_MISMATCH"); }
}
