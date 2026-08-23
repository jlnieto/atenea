package com.atenea.v2.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.api.v2.control.V2CapabilityPolicyResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class V2ControlValueObjectsTest {

    @Test
    void policySnapshotIsDefensiveAndExact() {
        Map<String, Long> revisions = new HashMap<>();
        revisions.put("atenea", 3L);
        V2CapabilityPolicy policy = new V2CapabilityPolicy(true, revisions);
        revisions.put("beautips", 3L);

        assertTrue(policy.allows("atenea", 3));
        assertFalse(policy.allows("atenea", 2));
        assertFalse(policy.allows("ATENEA", 3));
        assertFalse(policy.allows("beautips", 3));
        assertFalse(policy.allows("*", 3));
    }

    @Test
    void idempotencyAndProjectionRejectMalformedOrNonMonotonicInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new V2IdempotencyIdentity("", "a".repeat(64), "b".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new V2IdempotencyIdentity("request", "not-a-digest", "b".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new V2OperationProjection(1, true, null));
        assertThrows(IllegalArgumentException.class,
                () -> new V2OperationProjection(1, false, "a".repeat(64)));

        V2OperationProjection pending = new V2OperationProjection(1, false, null);
        V2OperationProjection terminal = pending.advance(2, true, "c".repeat(64));
        assertEquals(2, terminal.revision());
        assertThrows(IllegalStateException.class,
                () -> terminal.advance(3, true, "c".repeat(64)));
    }

    @Test
    void applicationAndApiDefaultsRemainDisabled() {
        V2ControlProperties properties = new V2ControlProperties();
        assertFalse(properties.isGlobalEnabled());
        assertTrue(properties.getProjectPolicyRevisions().isEmpty());
        assertFalse(properties.policy().allows("atenea", 1));

        V2CapabilityPolicyResponse response =
                V2CapabilityPolicyResponse.disabled("control-contracts", "atenea");
        assertFalse(response.globalEnabled());
        assertFalse(response.projectEnabled());
        assertFalse(response.allowed());
        assertThrows(IllegalArgumentException.class,
                () -> new V2CapabilityPolicyResponse(
                        "control-contracts", "atenea", false, true, 1, true));
    }
}
