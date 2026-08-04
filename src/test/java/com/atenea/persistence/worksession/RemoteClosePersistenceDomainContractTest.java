package com.atenea.persistence.worksession;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.api.worksession.AgentRunResponse;
import com.atenea.api.worksession.WorkSessionResponse;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemoteClosePersistenceDomainContractTest {

    @Test
    void definesTheClosedRemoteCloseStateVocabulary() throws Exception {
        Class<?> stateType = Class.forName(
                "com.atenea.persistence.worksession.RemoteCloseState");

        assertTrue(stateType.isEnum());
        assertArrayEquals(new String[]{
                "NOT_REQUIRED",
                "NOT_STARTED",
                "REQUESTED",
                "RECONCILING",
                "BLOCKED",
                "RELEASED",
                "UNVERIFIED_LEGACY"
        }, Arrays.stream(stateType.getEnumConstants()).map(Object::toString).toArray(String[]::new));
    }

    @Test
    void workSessionDomainPersistsTheImmutableMonotonicCloseProjection() throws Exception {
        Class<?> stateType = Class.forName(
                "com.atenea.persistence.worksession.RemoteCloseState");

        assertAccessor(WorkSessionEntity.class, "RemoteCloseState", stateType);
        assertAccessor(WorkSessionEntity.class, "RemoteCloseOperationId", UUID.class);
        assertAccessor(WorkSessionEntity.class, "RemoteCloseRevision", long.class);
        assertAccessor(WorkSessionEntity.class, "RemoteCloseReceiptSha256", String.class);
        assertAccessor(WorkSessionEntity.class, "RemoteCloseErrorCode", String.class);
        assertAccessor(WorkSessionEntity.class, "RemoteCloseRequestedAt", Instant.class);
        assertAccessor(WorkSessionEntity.class, "RemoteCloseUpdatedAt", Instant.class);
        assertAccessor(WorkSessionEntity.class, "RemoteCloseReleasedAt", Instant.class);
    }

    @Test
    void safeFailureAndNextActionAreProjectedWithoutRawWorkerDetail() throws Exception {
        assertAccessor(AgentRunEntity.class, "FailureCode", String.class);
        assertAccessor(
                AgentRunEntity.class,
                "RecoveryNextAction",
                AgentRunRecoveryNextAction.class);

        assertTrue(Arrays.stream(AgentRunRecoveryNextAction.values())
                .anyMatch(value -> value.name().equals("RECONCILE_REMOTE_CLOSE")));
        assertRecordComponent(AgentRunResponse.class, "failureCode", String.class);
        assertRecordComponent(
                AgentRunResponse.class,
                "recoveryNextAction",
                AgentRunRecoveryNextAction.class);

        Class<?> stateType = Class.forName(
                "com.atenea.persistence.worksession.RemoteCloseState");
        assertRecordComponent(WorkSessionResponse.class, "remoteCloseState", stateType);
        assertRecordComponent(WorkSessionResponse.class, "remoteCloseErrorCode", String.class);
        assertRecordComponent(
                WorkSessionResponse.class,
                "remoteCloseNextAction",
                AgentRunRecoveryNextAction.class);
    }

    private void assertAccessor(Class<?> owner, String suffix, Class<?> valueType) throws Exception {
        Method getter = owner.getMethod("get" + suffix);
        Method setter = owner.getMethod("set" + suffix, valueType);
        assertEquals(valueType, getter.getReturnType());
        assertEquals(void.class, setter.getReturnType());
    }

    private void assertRecordComponent(Class<?> owner, String name, Class<?> valueType) {
        RecordComponent component = Arrays.stream(owner.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing record component " + owner.getName() + "." + name));
        assertEquals(valueType, component.getType());
    }
}
