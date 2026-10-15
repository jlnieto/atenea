package com.atenea.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** Facts about required deliverables, not a deployment request or execution receipt. */
public record ChangeDeliveryReadiness(Long sessionId, String requirementsStatus, String headCommit,
        String integratedMergeCommit, List<DeliveryTarget> requiredTargets, boolean ready,
        MobileDeliveryService.DeploymentObservation backend, ReleaseControlClient.AndroidObservation android,
        String androidStatus, String errorCode, long observedAt) {
    public static List<DeliveryTarget> classify(List<String> paths) {
        if (paths==null || paths.isEmpty()) throw new DeliveryRejectedException("DELIVERY_DIFF_INCOMPLETE");
        var targets=java.util.EnumSet.noneOf(DeliveryTarget.class);
        for (String path:paths) {
            if (path==null || path.isBlank()) throw new DeliveryRejectedException("DELIVERY_DIFF_INCOMPLETE");
            if (path.startsWith("android/")) targets.add(DeliveryTarget.ANDROID_STABLE);
            else if (path.startsWith("src/") || path.equals("pom.xml") || path.equals("Dockerfile")
                    || path.startsWith("web/")) targets.add(DeliveryTarget.APP_PROD);
            else if (!path.startsWith("docs/") && !path.endsWith(".md") && !path.startsWith(".codex/")) {
                // Shared build/config/unknown changes must not be treated as documentation-only.
                targets.add(DeliveryTarget.APP_PROD);
                targets.add(DeliveryTarget.ANDROID_STABLE);
            }
        }
        return List.copyOf(targets);
    }

    public static boolean verifiedForClose(JsonNode proof, Long sessionId, String head, String merge) {
        long now=Instant.now().getEpochSecond();
        if (!proof.isObject() || !"CONFIRMED".equals(proof.path("requirementsStatus").asText())
                || !proof.path("ready").asBoolean(false) || proof.path("sessionId").asLong(-1)!=sessionId
                || !head.equals(proof.path("headCommit").asText()) || !merge.equals(proof.path("integratedMergeCommit").asText())
                || proof.path("observedAt").asLong(0)<now-60 || proof.path("observedAt").asLong(0)>now+5
                || !proof.path("requiredTargets").isArray()) return false;
        var targets=new java.util.HashSet<String>();
        for (var target:proof.path("requiredTargets")) if (!target.isTextual() || !targets.add(target.asText())
                || !List.of("APP_PROD","ANDROID_STABLE").contains(target.asText())) return false;
        if (targets.contains("APP_PROD") && (!"DEPLOYED".equals(proof.path("backend").path("status").asText())
                || !proof.path("backend").path("healthy").asBoolean(false)
                || proof.path("backend").path("sessionId").asLong(-1)!=sessionId
                || !merge.equals(proof.path("backend").path("integratedMergeCommit").asText())
                || !receipt(proof.path("backend"),now))) return false;
        return !targets.contains("ANDROID_STABLE") || ("INCLUDED".equals(proof.path("androidStatus").asText())
                && receipt(proof.path("android"),now)
                && proof.path("android").path("sourceCommit").asText().matches("[0-9a-f]{40}")
                && proof.path("android").path("apkSha256").asText().matches("[0-9a-f]{64}")
                && proof.path("android").path("receiptSha256").asText().matches("[0-9a-f]{64}")
                && proof.path("android").path("versionCode").asInt(0)>0);
    }

    private static boolean receipt(JsonNode value,long now) {
        try {
            for (String key:List.of("planId","operationId")) if (!java.util.UUID.fromString(value.path(key).asText()).toString().equals(value.path(key).asText())) return false;
            return value.path("observedAt").isIntegralNumber() && value.path("observedAt").asLong()>=now-60
                    && value.path("observedAt").asLong()<=now+5 && value.path("sourceCommit").asText().matches("[0-9a-f]{40}")
                    && value.path("receiptSha256").asText().matches("[0-9a-f]{64}");
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
