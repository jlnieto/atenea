package com.atenea.v2.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class V2ControlContractRedTest {

    private static final String CONTROL_PACKAGE = "com.atenea.v2.control.";

    @Test
    void policyRequiresBothGlobalGateAndExactProjectRevision() throws Exception {
        Class<?> policyType = controlType("V2CapabilityPolicy");
        Constructor<?> constructor = policyType.getConstructor(boolean.class, Map.class);
        Method allows = policyType.getMethod("allows", String.class, long.class);

        Object globalOff = constructor.newInstance(false, Map.of("atenea", 7L));
        Object projectOff = constructor.newInstance(true, Map.of());
        Object exactAtenea = constructor.newInstance(true, Map.of("atenea", 7L));
        Object wildcard = constructor.newInstance(true, Map.of("*", 7L));

        assertFalse(invokeBoolean(allows, globalOff, "atenea", 7L));
        assertFalse(invokeBoolean(allows, projectOff, "atenea", 7L));
        assertTrue(invokeBoolean(allows, exactAtenea, "atenea", 7L));
        assertFalse(invokeBoolean(allows, exactAtenea, "atenea", 8L));
        assertFalse(invokeBoolean(allows, exactAtenea, "ATENEA", 7L));
        assertFalse(invokeBoolean(allows, exactAtenea, "beautips", 7L));
        assertFalse(invokeBoolean(allows, wildcard, "atenea", 7L));
    }

    @Test
    void failureVocabularyIsClosedAndOnlyTransportIsRetryable() throws Exception {
        Class<?> categoryType = controlType("V2FailureCategory");

        assertTrue(categoryType.isEnum());
        assertArrayEquals(
                new String[]{"TRANSPORT", "CAPACITY", "VALIDATION", "POLICY", "OWNERSHIP"},
                Arrays.stream(categoryType.getEnumConstants())
                        .map(Object::toString)
                        .toArray(String[]::new));

        Method retryable = categoryType.getMethod("isTransportRetryable");
        for (Object category : categoryType.getEnumConstants()) {
            assertEquals(
                    category.toString().equals("TRANSPORT"),
                    invokeBoolean(retryable, category),
                    () -> category + " must have a deterministic retry classification");
        }
    }

    @Test
    void idempotencyIdentityRejectsRequestOrTargetCollisions() throws Exception {
        Class<?> identityType = controlType("V2IdempotencyIdentity");
        Constructor<?> constructor = identityType.getConstructor(
                String.class,
                String.class,
                String.class);
        Method sameRequestAs = identityType.getMethod("sameRequestAs", identityType);

        Object stored = constructor.newInstance("request-1", "a".repeat(64), "b".repeat(64));
        Object exactRetry = constructor.newInstance("request-1", "a".repeat(64), "b".repeat(64));
        Object changedRequest = constructor.newInstance("request-1", "c".repeat(64), "b".repeat(64));
        Object changedTarget = constructor.newInstance("request-1", "a".repeat(64), "d".repeat(64));
        Object changedKey = constructor.newInstance("request-2", "a".repeat(64), "b".repeat(64));

        assertTrue(invokeBoolean(sameRequestAs, stored, exactRetry));
        assertFalse(invokeBoolean(sameRequestAs, stored, changedRequest));
        assertFalse(invokeBoolean(sameRequestAs, stored, changedTarget));
        assertFalse(invokeBoolean(sameRequestAs, stored, changedKey));
    }

    @Test
    void operationProjectionAdvancesMonotonicallyAndKeepsTerminalReceiptImmutable()
            throws Exception {
        Class<?> projectionType = controlType("V2OperationProjection");
        Constructor<?> constructor = projectionType.getConstructor(
                long.class,
                boolean.class,
                String.class);
        Method advance = projectionType.getMethod(
                "advance",
                long.class,
                boolean.class,
                String.class);

        Object pending = constructor.newInstance(3L, false, null);
        String receipt = "e".repeat(64);
        Object terminal = advance.invoke(pending, 4L, true, receipt);

        assertEquals(4L, projectionType.getMethod("revision").invoke(terminal));
        assertEquals(true, projectionType.getMethod("terminal").invoke(terminal));
        assertEquals(receipt, projectionType.getMethod("receiptSha256").invoke(terminal));

        assertInvocationCause(
                IllegalArgumentException.class,
                () -> advance.invoke(pending, 3L, false, null));
        assertInvocationCause(
                IllegalArgumentException.class,
                () -> advance.invoke(pending, 2L, false, null));
        assertInvocationCause(
                IllegalStateException.class,
                () -> advance.invoke(terminal, 5L, true, "f".repeat(64)));
    }

    private static Class<?> controlType(String simpleName) throws ClassNotFoundException {
        return Class.forName(CONTROL_PACKAGE + simpleName);
    }

    private static boolean invokeBoolean(Method method, Object owner, Object... arguments)
            throws ReflectiveOperationException {
        return (boolean) method.invoke(owner, arguments);
    }

    private static void assertInvocationCause(
            Class<? extends Throwable> expected,
            ThrowingInvocation invocation) {
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
