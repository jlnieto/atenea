package com.atenea.service.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.api.developmentchange.DevelopmentChangeController;
import com.atenea.persistence.developmentchange.DevelopmentChangeOperationKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DevelopmentChangeLegacyBindContractTest {

    private static final String CONTRACT =
            "/atenea-v2/development-change-legacy-bind/legacy-bind-contract.json";
    private static final long PROTECTED_WORK_SESSION_ID = 19L;
    private static final String PROTECTED_REMOTE_SESSION_ID =
            "6547081d-895e-4be1-a8fd-d115b7743cdf";
    private static final Pattern WORK_SESSION_DATA_MUTATION = Pattern.compile(
            "(?is)\\b(?:UPDATE\\s+work_session|INSERT\\s+INTO\\s+work_session"
                    + "|DELETE\\s+FROM\\s+work_session)\\b");
    private static final Set<String> FORBIDDEN_CONTENT_FIELDS = Set.of(
            "prompt",
            "response",
            "messageText",
            "attachmentContent",
            "token",
            "cookie",
            "secret",
            "credentialId",
            "ipAddress",
            "rawUserAgent");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void contractIsSpecifiedButBothRuntimeSurfacesRemainAbsentAndDisabled()
            throws Exception {
        JsonNode root = readContract();

        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("M2_LEGACY_BIND_CONTRACT", root.path("kind").asText());
        assertEquals("LEGACY_BIND", root.path("operationKind").asText());
        assertEquals("SPECIFIED_DISABLED", root.path("status").asText());
        assertFalse(root.path("runtimeSurfacePresent").asBoolean());
        assertFalse(root.path("planEnabledByDefault").asBoolean());
        assertFalse(root.path("confirmationEnabledByDefault").asBoolean());
        assertFalse(root.path("automaticBackfill").asBoolean());
        assertFalse(root.path("contractMigrationCreated").asBoolean());
        assertFalse(Arrays.stream(DevelopmentChangeOperationKind.values())
                .anyMatch(value -> "LEGACY_BIND".equals(value.name())));
        assertFalse(Arrays.stream(DevelopmentChangeController.class.getDeclaredMethods())
                .map(method -> method.getName().toUpperCase(Locale.ROOT))
                .anyMatch(name -> name.contains("LEGACY")));
    }

    @Test
    void planIsFiniteServerOwnedAndOperationallyReadOnly() throws Exception {
        JsonNode plan = readContract().path("plan");

        assertTrue(plan.path("operationallyReadOnly").asBoolean());
        assertTrue(plan.path("durableBeforeConfirmation").asBoolean());
        assertEquals(600, plan.path("maximumTtlSeconds").asInt());
        assertEquals("LEGACY_ADOPTED", plan.path("identityMode").asText());
        assertTrue(plan.path("clientOwnedSelectors").isEmpty());
        assertFalse(plan.path("mayCreateBranch").asBoolean());
        assertFalse(plan.path("mayCreateWorkspace").asBoolean());
        assertFalse(plan.path("mayMoveSession").asBoolean());
        assertFalse(plan.path("mayContactWorker").asBoolean());
        assertFalse(plan.path("mayRunPrompt").asBoolean());
        assertEquals(Set.of(
                        "projectId",
                        "workSessionId",
                        "remoteSessionId",
                        "proposedChangeKey",
                        "baseRef",
                        "baseCommit",
                        "retainedHead",
                        "workspaceBranch",
                        "workspaceIdentity",
                        "selectedWorkerId",
                        "workloadKind",
                        "workSessionVersion",
                        "sourceFingerprintSha256",
                        "sessionFingerprintSha256",
                        "ownershipFingerprintSha256",
                        "projectPolicyRevision"),
                textSet(plan.path("serverOwnedFields")));
        assertTrue(textSet(plan.path("requiredFields"))
                .containsAll(textSet(plan.path("serverOwnedFields"))));
    }

    @Test
    void confirmationRequiresExactH5AndSingleUsePrivilegedAuthorization()
            throws Exception {
        JsonNode root = readContract();
        JsonNode confirmation = root.path("confirmation");
        JsonNode binding = root.path("privilegedActionBinding");

        assertFalse(confirmation.path("implemented").asBoolean());
        assertEquals("H5", confirmation.path("requiresHumanGate").asText());
        assertTrue(confirmation.path("requiresPrivilegedActionAuthorization").asBoolean());
        assertEquals("LEGACY_BIND", confirmation.path("privilegedActionKind").asText());
        assertTrue(confirmation.path("singleUse").asBoolean());
        assertTrue(confirmation.path("idempotent").asBoolean());
        assertEquals(1, confirmation.path("concurrentWinners").asInt());
        assertEquals(Set.of(
                        "planId",
                        "planFingerprintSha256",
                        "h5GateRequestSha256",
                        "privilegedActionAuthorization",
                        "idempotencyKey"),
                textSet(confirmation.path("requiredInputs")));
        assertEquals("LEGACY_BIND", binding.path("actionKind").asText());
        assertEquals(Set.of(
                        "CREATE_LEGACY_ADOPTED_DEVELOPMENT_CHANGE",
                        "SET_EXACT_WORK_SESSION_DEVELOPMENT_CHANGE_ID",
                        "PERSIST_SANITIZED_RECEIPT"),
                textSet(confirmation.path("allowedAtomicEffects")));
        assertTrue(textSet(confirmation.path("forbiddenEffects"))
                .containsAll(Set.of(
                        "MOVE_BRANCH",
                        "MOVE_WORKSPACE",
                        "CHANGE_WORKER",
                        "CHANGE_REMOTE_SESSION",
                        "CREATE_AGENT_RUN",
                        "RUN_PROMPT",
                        "AUTOMATIC_BACKFILL")));
    }

    @Test
    void protectedWorkSessionRequiresASeparatelyExactH5AndNeverGenericBinding()
            throws Exception {
        JsonNode exclusion = readContract().path("protectedExclusion");

        assertEquals(PROTECTED_WORK_SESSION_ID, exclusion.path("workSessionId").asLong());
        assertEquals(PROTECTED_REMOTE_SESSION_ID, exclusion.path("remoteSessionId").asText());
        assertEquals("EXCLUDED_REQUIRES_EXPLICIT_H5",
                exclusion.path("defaultDecision").asText());
        assertFalse(exclusion.path("genericPlanAllowed").asBoolean());
        assertFalse(exclusion.path("genericConfirmationAllowed").asBoolean());
        assertFalse(exclusion.path("automaticBinding").asBoolean());
        assertEquals(Set.of(
                        "projectId",
                        "workSessionId",
                        "remoteSessionId",
                        "proposedChangeKey",
                        "planFingerprintSha256",
                        "ownershipFingerprintSha256"),
                textSet(exclusion.path("exactH5MustName")));
    }

    @Test
    void everyFailureCaseIsClosedWithoutMutation() throws Exception {
        JsonNode cases = readContract().path("failureCases");
        Set<String> names = new HashSet<>();

        assertEquals(11, cases.size());
        for (JsonNode testCase : cases) {
            assertTrue(names.add(testCase.path("case").asText()));
            assertTrue(Set.of("BLOCKED", "STALE", "EXCLUDED_REQUIRES_EXPLICIT_H5")
                    .contains(testCase.path("state").asText()));
            assertEquals(0, testCase.path("mutations").asInt());
        }
    }

    @Test
    void expandAndDurabilityMigrationsContainNoAutomaticWorkSessionBackfill()
            throws Exception {
        for (String migration : Set.of(
                "/db/migration/V74__expand_development_change_control.sql",
                "/db/migration/V75__persist_development_change_api_operations.sql",
                "/db/migration/V76__persist_development_change_workspace_operations.sql")) {
            String sql = readResource(migration);
            assertFalse(WORK_SESSION_DATA_MUTATION.matcher(sql).find(),
                    () -> migration + " must not mutate work_session rows");
        }
    }

    @Test
    void contractContainsNoOperationalOrSensitiveContent() throws Exception {
        JsonNode root = readContract();

        assertTrue(root.path("synthetic").asBoolean());
        assertFalse(root.path("containsOperationalContent").asBoolean());
        assertNoForbiddenContentFields(root);
    }

    private JsonNode readContract() throws IOException {
        return objectMapper.readTree(readResource(CONTRACT));
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, "resource must exist: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static void assertNoForbiddenContentFields(JsonNode node) {
        if (node.isObject()) {
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                assertFalse(FORBIDDEN_CONTENT_FIELDS.stream()
                                .map(value -> value.toLowerCase(Locale.ROOT))
                                .anyMatch(value -> value.equals(field.toLowerCase(Locale.ROOT))),
                        () -> "contract must not contain sensitive field: " + field);
                assertNoForbiddenContentFields(node.get(field));
            }
        } else if (node.isArray()) {
            node.forEach(DevelopmentChangeLegacyBindContractTest::assertNoForbiddenContentFields);
        }
    }
}
