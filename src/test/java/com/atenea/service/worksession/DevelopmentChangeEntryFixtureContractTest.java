package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DevelopmentChangeEntryFixtureContractTest {

    private static final String FIXTURE = "/atenea-v2/development-change-entry/entry-fixtures.json";
    private static final long PROTECTED_WORK_SESSION_ID = 19L;
    private static final String PROTECTED_REMOTE_SESSION_ID = "6547081d-895e-4be1-a8fd-d115b7743cdf";
    private static final Set<String> FORBIDDEN_CONTENT_FIELDS = Set.of(
            "prompt",
            "response",
            "messageText",
            "attachmentContent",
            "token",
            "cookie",
            "secret",
            "credentialId"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fixtureIsSyntheticAndContainsNoOperationalContent() throws Exception {
        JsonNode root = readFixture();

        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("M2_DEVELOPMENT_CHANGE_ENTRY_FIXTURE", root.path("kind").asText());
        assertTrue(root.path("synthetic").asBoolean());
        assertFalse(root.path("containsOperationalContent").asBoolean());
        assertNoForbiddenContentFields(root);
    }

    @Test
    void legacyFixtureRemainsUnboundBehindTheProjectGuard() throws Exception {
        JsonNode legacy = readFixture().path("legacySessions").get(0);

        assertTrue(legacy.path("workSessionId").asLong() >= 900000L);
        assertTrue(legacy.path("developmentChangeId").isNull());
        assertEquals("OPEN", legacy.path("status").asText());
        assertEquals("LEGACY_PROJECT_GUARD", legacy.path("routingMode").asText());
        assertFalse(legacy.path("automaticBinding").asBoolean());
    }

    @Test
    void twoChangesUseDistinctServerDerivedBranchesAndOwnership() throws Exception {
        JsonNode root = readFixture();
        JsonNode policy = root.path("policy");
        JsonNode changes = root.path("multiBranchProject").path("changes");
        Set<String> branches = new HashSet<>();
        Set<String> workspaces = new HashSet<>();
        Set<String> remoteSessions = new HashSet<>();

        assertEquals(2, changes.size());
        assertTrue(policy.path("clientOwnedSelectors").isArray());
        assertTrue(policy.path("clientOwnedSelectors").isEmpty());
        assertTrue(policy.path("oneActiveSessionPerChange").asBoolean());
        for (JsonNode change : changes) {
            String changeKey = change.path("changeKey").asText();
            assertEquals(policy.path("branchPrefix").asText() + changeKey,
                    change.path("workspaceBranch").asText());
            assertEquals(policy.path("workspaceIdentityPrefix").asText() + changeKey,
                    change.path("workspaceIdentity").asText());
            assertEquals(change.path("developmentChangeId").asLong(),
                    change.path("workSession").path("developmentChangeId").asLong());
            assertEquals("OPEN", change.path("workSession").path("status").asText());
            assertTrue(branches.add(change.path("workspaceBranch").asText()));
            assertTrue(workspaces.add(change.path("workspaceIdentity").asText()));
            assertTrue(remoteSessions.add(change.path("workSession").path("remoteSessionId").asText()));
        }
    }

    @Test
    void workSession19AndItsRemoteOwnershipAreExactlyExcluded() throws Exception {
        JsonNode root = readFixture();
        JsonNode exclusion = root.path("protectedExclusions").get(0);

        assertEquals(PROTECTED_WORK_SESSION_ID, exclusion.path("workSessionId").asLong());
        assertEquals(PROTECTED_REMOTE_SESSION_ID, exclusion.path("remoteSessionId").asText());
        assertEquals("EXCLUDED_REQUIRES_EXPLICIT_H5", exclusion.path("decision").asText());
        assertFalse(exclusion.path("automaticBinding").asBoolean());
        assertFalse(exclusion.path("mutationAllowed").asBoolean());

        for (JsonNode legacy : root.path("legacySessions")) {
            assertFalse(legacy.path("workSessionId").asLong() == PROTECTED_WORK_SESSION_ID);
        }
        for (JsonNode change : root.path("multiBranchProject").path("changes")) {
            JsonNode session = change.path("workSession");
            assertFalse(session.path("workSessionId").asLong() == PROTECTED_WORK_SESSION_ID);
            assertFalse(PROTECTED_REMOTE_SESSION_ID.equals(session.path("remoteSessionId").asText()));
        }
    }

    @Test
    void everyNegativeCaseFailsClosedWithoutMutation() throws Exception {
        JsonNode cases = readFixture().path("negativeCases");
        Set<String> names = new HashSet<>();

        assertEquals(7, cases.size());
        for (JsonNode testCase : cases) {
            assertTrue(names.add(testCase.path("case").asText()));
            assertTrue(Set.of("OWNERSHIP", "VALIDATION", "POLICY")
                    .contains(testCase.path("expectedCategory").asText()));
            assertTrue(Set.of("BLOCKED", "STALE", "EXCLUDED_REQUIRES_EXPLICIT_H5")
                    .contains(testCase.path("expectedState").asText()));
            assertEquals(0, testCase.path("mutations").asInt());
        }
    }

    private JsonNode readFixture() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(FIXTURE)) {
            assertNotNull(input, "fixture resource must exist");
            return objectMapper.readTree(input);
        }
    }

    private static void assertNoForbiddenContentFields(JsonNode node) {
        if (node.isObject()) {
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                assertFalse(FORBIDDEN_CONTENT_FIELDS.stream()
                                .map(value -> value.toLowerCase(Locale.ROOT))
                                .anyMatch(value -> value.equals(field.toLowerCase(Locale.ROOT))),
                        () -> "fixture must not contain operational content field: " + field);
                assertNoForbiddenContentFields(node.get(field));
            }
        } else if (node.isArray()) {
            node.forEach(DevelopmentChangeEntryFixtureContractTest::assertNoForbiddenContentFields);
        }
    }
}
