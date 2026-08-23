package com.atenea.auth.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperatorSessionHardeningRedTest {

    private static final String SESSION_PACKAGE = "com.atenea.auth.session.";

    @Test
    void currentGenerationRotatesOnceAndReplayRevokesTheCompleteFamily() throws Exception {
        Class<?> stateType = sessionType("SessionFamilyState");
        Constructor<?> constructor = stateType.getConstructor(UUID.class, long.class, boolean.class);
        Method consume = stateType.getMethod("consume", long.class);

        UUID familyId = UUID.randomUUID();
        Object initial = constructor.newInstance(familyId, 0L, false);
        Object rotation = consume.invoke(initial, 0L);
        Object rotated = transitionState(rotation);

        assertEquals("ROTATED", transitionOutcome(rotation));
        assertEquals(1L, transitionSuccessorGeneration(rotation));
        assertEquals(familyId, stateType.getMethod("familyId").invoke(rotated));
        assertEquals(1L, stateType.getMethod("currentGeneration").invoke(rotated));
        assertFalse((boolean) stateType.getMethod("revoked").invoke(rotated));

        Object replay = consume.invoke(rotated, 0L);
        Object revoked = transitionState(replay);
        assertEquals("REPLAY_REVOKED", transitionOutcome(replay));
        assertNull(transitionSuccessorGeneration(replay));
        assertTrue((boolean) stateType.getMethod("revoked").invoke(revoked));
    }

    @Test
    void replayRevocationDoesNotRevokeAnUnrelatedFamily() throws Exception {
        Class<?> stateType = sessionType("SessionFamilyState");
        Constructor<?> constructor = stateType.getConstructor(UUID.class, long.class, boolean.class);
        Method consume = stateType.getMethod("consume", long.class);

        Object compromised = constructor.newInstance(UUID.randomUUID(), 2L, false);
        Object unrelated = constructor.newInstance(UUID.randomUUID(), 0L, false);
        Object replay = consume.invoke(compromised, 1L);

        assertEquals("REPLAY_REVOKED", transitionOutcome(replay));
        assertTrue((boolean) stateType.getMethod("revoked").invoke(transitionState(replay)));
        assertFalse((boolean) stateType.getMethod("revoked").invoke(unrelated));

        Object futureGeneration = consume.invoke(unrelated, 4L);
        assertEquals("INVALID_GENERATION", transitionOutcome(futureGeneration));
        assertFalse((boolean) stateType.getMethod("revoked")
                .invoke(transitionState(futureGeneration)));
    }

    @Test
    void accessRequiresExactCredentialAndRoleVersions() throws Exception {
        Class<?> versionsType = sessionType("SessionVersions");
        Constructor<?> constructor = versionsType.getConstructor(long.class, long.class);
        Method matches = versionsType.getMethod("matches", long.class, long.class);
        Object issued = constructor.newInstance(7L, 3L);

        assertTrue((boolean) matches.invoke(issued, 7L, 3L));
        assertFalse((boolean) matches.invoke(issued, 8L, 3L));
        assertFalse((boolean) matches.invoke(issued, 7L, 4L));
        assertFalse((boolean) matches.invoke(issued, 6L, 2L));

        assertInvocationCause(IllegalArgumentException.class,
                () -> constructor.newInstance(-1L, 0L));
        assertInvocationCause(IllegalArgumentException.class,
                () -> constructor.newInstance(0L, -1L));
    }

    @Test
    void sessionInventoryContainsOnlySanitizedOperationalMetadata() throws Exception {
        Class<?> projectionType = sessionType("SessionInventoryProjection");
        assertTrue(projectionType.isRecord());
        assertArrayEquals(
                new String[]{
                        "familyId",
                        "clientType",
                        "deviceLabel",
                        "createdAt",
                        "lastUsedAt",
                        "absoluteExpiresAt",
                        "state",
                        "current"},
                Arrays.stream(projectionType.getRecordComponents())
                        .map(component -> component.getName())
                        .toArray(String[]::new));
        Arrays.stream(projectionType.getRecordComponents())
                .map(component -> component.getName().toLowerCase(Locale.ROOT))
                .forEach(name -> {
                    assertFalse(name.contains("token"));
                    assertFalse(name.contains("hash"));
                    assertFalse(name.contains("ip"));
                    assertFalse(name.contains("useragent"));
                    assertFalse(name.contains("secret"));
                });

        Constructor<?> constructor = projectionType.getConstructor(
                UUID.class,
                String.class,
                String.class,
                Instant.class,
                Instant.class,
                Instant.class,
                sessionType("SessionInventoryState"),
                boolean.class);
        Instant createdAt = Instant.parse("2026-08-12T10:00:00Z");
        Object active = Enum.valueOf(
                sessionType("SessionInventoryState").asSubclass(Enum.class), "ACTIVE");
        Object projection = constructor.newInstance(
                UUID.randomUUID(),
                "WEB",
                "Work browser",
                createdAt,
                createdAt.plusSeconds(60),
                createdAt.plusSeconds(3600),
                active,
                true);
        assertEquals("Work browser", projectionType.getMethod("deviceLabel").invoke(projection));

        assertInvocationCause(IllegalArgumentException.class, () -> constructor.newInstance(
                UUID.randomUUID(), "web", "Work browser", createdAt, createdAt,
                createdAt.plusSeconds(3600), active, false));
        assertInvocationCause(IllegalArgumentException.class, () -> constructor.newInstance(
                UUID.randomUUID(), "WEB", " unsafe label ", createdAt, createdAt,
                createdAt.plusSeconds(3600), active, false));
        assertInvocationCause(IllegalArgumentException.class, () -> constructor.newInstance(
                UUID.randomUUID(), "WEB", "line\nbreak", createdAt, createdAt,
                createdAt.plusSeconds(3600), active, false));
        assertInvocationCause(IllegalArgumentException.class, () -> constructor.newInstance(
                UUID.randomUUID(), "WEB", "Unrounded", createdAt.plusSeconds(1), createdAt.plusSeconds(60),
                createdAt.plusSeconds(3600), active, false));
    }

    private static Class<?> sessionType(String simpleName) throws ClassNotFoundException {
        return Class.forName(SESSION_PACKAGE + simpleName);
    }

    private static Object transitionState(Object transition) throws ReflectiveOperationException {
        return transition.getClass().getMethod("state").invoke(transition);
    }

    private static String transitionOutcome(Object transition) throws ReflectiveOperationException {
        return String.valueOf(transition.getClass().getMethod("outcome").invoke(transition));
    }

    private static Object transitionSuccessorGeneration(Object transition)
            throws ReflectiveOperationException {
        return transition.getClass().getMethod("successorGeneration").invoke(transition);
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
