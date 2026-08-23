package com.atenea.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DevelopmentChangeControlRedTest {

    private static final String CHANGE_PACKAGE = "com.atenea.developmentchange.";

    @Test
    void twoChangesForOneProjectDeriveDistinctServerOwnedIdentities() throws Exception {
        Class<?> identityType = changeType("DevelopmentChangeIdentity");
        Constructor<?> constructor = identityType.getConstructor(
                UUID.class, long.class, String.class, String.class, String.class);
        UUID firstKey = UUID.fromString("8bf60472-3c0e-49aa-99bf-6dc3c7e60eaf");
        UUID secondKey = UUID.fromString("17f120f6-79e2-49e4-bd13-23db520d1374");

        Object first = identity(constructor, firstKey, 7L, "synthetic-worker-01");
        Object second = identity(constructor, secondKey, 7L, "synthetic-worker-01");

        assertEquals(firstKey, identityType.getMethod("changeKey").invoke(first));
        assertEquals(7L, identityType.getMethod("projectId").invoke(first));
        assertEquals("atenea/change-" + firstKey,
                identityType.getMethod("workspaceBranch").invoke(first));
        assertEquals("remote:synthetic-worker-01:change:" + firstKey,
                identityType.getMethod("workspaceIdentity").invoke(first));
        assertFalse(identityType.getMethod("workspaceBranch").invoke(first)
                .equals(identityType.getMethod("workspaceBranch").invoke(second)));
        assertFalse(identityType.getMethod("workspaceIdentity").invoke(first)
                .equals(identityType.getMethod("workspaceIdentity").invoke(second)));
    }

    @Test
    void branchOrWorkspaceMismatchFailsClosedInsteadOfBeingAdopted() throws Exception {
        Class<?> identityType = changeType("DevelopmentChangeIdentity");
        Constructor<?> constructor = identityType.getConstructor(
                UUID.class, long.class, String.class, String.class, String.class);
        UUID changeKey = UUID.fromString("8bf60472-3c0e-49aa-99bf-6dc3c7e60eaf");

        assertInvocationCause(IllegalArgumentException.class, () -> constructor.newInstance(
                changeKey,
                7L,
                "synthetic-worker-01",
                "atenea/change-foreign",
                "remote:synthetic-worker-01:change:" + changeKey));
        assertInvocationCause(IllegalArgumentException.class, () -> constructor.newInstance(
                changeKey,
                7L,
                "synthetic-worker-01",
                "atenea/change-" + changeKey,
                "remote:foreign-worker:change:" + changeKey));
        assertInvocationCause(IllegalArgumentException.class, () -> constructor.newInstance(
                changeKey,
                7L,
                "worker/selected/by/client",
                "atenea/change-" + changeKey,
                "remote:worker/selected/by/client:change:" + changeKey));
    }

    @Test
    void oneActiveSessionPerChangeStillAllowsTwoChangesForOneProject() throws Exception {
        Class<?> indexType = changeType("DevelopmentChangeActiveSessionIndex");
        Constructor<?> constructor = indexType.getConstructor();
        Method open = indexType.getMethod("open", UUID.class, long.class, long.class);
        Method activeSessionId = indexType.getMethod("activeSessionId", UUID.class);
        Method activeCountForProject = indexType.getMethod("activeCountForProject", long.class);
        UUID firstKey = UUID.fromString("8bf60472-3c0e-49aa-99bf-6dc3c7e60eaf");
        UUID secondKey = UUID.fromString("17f120f6-79e2-49e4-bd13-23db520d1374");

        Object empty = constructor.newInstance();
        Object first = open.invoke(empty, firstKey, 7L, 920001L);
        Object second = open.invoke(first, secondKey, 7L, 920002L);

        assertEquals(920001L, activeSessionId.invoke(second, firstKey));
        assertEquals(920002L, activeSessionId.invoke(second, secondKey));
        assertEquals(2L, activeCountForProject.invoke(second, 7L));
        assertInvocationCause(IllegalStateException.class,
                () -> open.invoke(second, firstKey, 7L, 920003L));
    }

    @Test
    void sourceAdvanceIsMonotonicAndInvalidatesEveryDownstreamProjection() throws Exception {
        Class<?> sourceType = changeType("DevelopmentChangeSourceProjection");
        Constructor<?> constructor = sourceType.getConstructor(
                long.class, String.class, String.class);
        Method observe = sourceType.getMethod(
                "observe", String.class, String.class, boolean.class);
        String initialFingerprint = "a".repeat(64);
        String changedFingerprint = "b".repeat(64);
        String baseCommit = "1".repeat(40);
        String advancedCommit = "2".repeat(40);
        Object initial = constructor.newInstance(4L, initialFingerprint, baseCommit);

        Object unchanged = observe.invoke(initial, initialFingerprint, baseCommit, false);
        Object changed = observe.invoke(initial, changedFingerprint, baseCommit, true);
        Object stale = observe.invoke(initial, changedFingerprint, advancedCommit, true);

        assertTransition(unchanged, "UNCHANGED", 4L, "CLEAN", false, false, Set.of());
        assertTransition(changed, "SOURCE_CHANGED", 5L, "DIRTY", true, false,
                Set.of("VALIDATION", "REVIEW", "INTEGRATION", "RELEASE"));
        assertTransition(stale, "CANONICAL_ADVANCED", 5L, "STALE", true, true,
                Set.of("VALIDATION", "REVIEW", "INTEGRATION", "RELEASE"));

        assertInvocationCause(IllegalArgumentException.class,
                () -> constructor.newInstance(-1L, initialFingerprint, baseCommit));
        assertInvocationCause(IllegalArgumentException.class,
                () -> constructor.newInstance(0L, "not-a-sha256", baseCommit));
    }

    private static Object identity(
            Constructor<?> constructor,
            UUID changeKey,
            long projectId,
            String workerId
    ) throws ReflectiveOperationException {
        return constructor.newInstance(
                changeKey,
                projectId,
                workerId,
                "atenea/change-" + changeKey,
                "remote:" + workerId + ":change:" + changeKey);
    }

    private static void assertTransition(
            Object transition,
            String outcome,
            long revision,
            String sourceState,
            boolean invalidatesDownstream,
            boolean reconciliationRequired,
            Set<String> staleProjections
    ) throws ReflectiveOperationException {
        Class<?> type = transition.getClass();
        assertEquals(outcome, String.valueOf(type.getMethod("outcome").invoke(transition)));
        assertEquals(revision, type.getMethod("sourceRevision").invoke(transition));
        assertEquals(sourceState, String.valueOf(type.getMethod("sourceState").invoke(transition)));
        assertEquals(invalidatesDownstream,
                type.getMethod("invalidatesDownstream").invoke(transition));
        assertEquals(reconciliationRequired,
                type.getMethod("reconciliationRequired").invoke(transition));
        Object[] actual = ((Set<?>) type.getMethod("staleProjections").invoke(transition)).toArray();
        assertEquals(staleProjections, Arrays.stream(actual)
                .map(String::valueOf)
                .collect(Collectors.toSet()));
    }

    private static Class<?> changeType(String simpleName) throws ClassNotFoundException {
        return Class.forName(CHANGE_PACKAGE + simpleName);
    }

    private static void assertInvocationCause(
            Class<? extends Throwable> expected,
            ThrowingInvocation invocation
    ) {
        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                invocation::invoke);
        assertInstanceOf(expected, failure.getCause());
    }

    @FunctionalInterface
    private interface ThrowingInvocation {
        void invoke() throws Exception;
    }
}
