package com.atenea.service.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.atenea.developmentchange.DevelopmentChangeProperties;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateEntity;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateRepository;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyEntity;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

@ExtendWith(MockitoExtension.class)
class DevelopmentChangePolicyTest {

    @Mock private V2GlobalCapabilityGateRepository globalRepository;
    @Mock private V2ProjectCapabilityPolicyRepository projectRepository;

    private DevelopmentChangeProperties properties;
    private DevelopmentChangePolicy policy;

    @BeforeEach
    void setUp() {
        properties = new DevelopmentChangeProperties();
        policy = new DevelopmentChangePolicy(properties, globalRepository, projectRepository);
    }

    @Test
    void defaultsClosedBeforeReadingDurablePolicy() {
        var decision = policy.decide(7L, false);

        assertFalse(decision.allowed());
        assertEquals("DEVELOPMENT_CHANGE_MUTATIONS_DISABLED", decision.failureCode());
    }

    @Test
    void sessionBindingRequiresItsIndependentDisabledByDefaultFlag() {
        properties.setMutationsEnabled(true);

        var decision = policy.decide(7L, true);

        assertFalse(decision.allowed());
        assertEquals("DEVELOPMENT_CHANGE_SESSION_BINDING_DISABLED", decision.failureCode());
    }

    @Test
    void missingOrInvalidGlobalGateFailsClosed() {
        properties.setMutationsEnabled(true);
        when(globalRepository.findById(DevelopmentChangePolicy.CAPABILITY))
                .thenReturn(Optional.empty(), Optional.of(global(true, 0)));

        var missing = policy.decide(7L, false);
        var invalidRevision = policy.decide(7L, false);

        assertEquals("V2_GLOBAL_GATE_DISABLED", missing.failureCode());
        assertEquals("V2_GLOBAL_GATE_REVISION_INVALID", invalidRevision.failureCode());
    }

    @Test
    void missingDisabledOrAmbiguousExactProjectPolicyFailsClosed() {
        properties.setMutationsEnabled(true);
        when(globalRepository.findById(DevelopmentChangePolicy.CAPABILITY))
                .thenReturn(Optional.of(global(true, 1)));
        when(projectRepository.findByProjectIdAndCapability(
                7L, DevelopmentChangePolicy.CAPABILITY))
                .thenReturn(Optional.empty(), Optional.of(project(false, 3)))
                .thenThrow(new IncorrectResultSizeDataAccessException(2));

        assertEquals("V2_PROJECT_POLICY_DISABLED", policy.decide(7L, false).failureCode());
        assertEquals("V2_PROJECT_POLICY_DISABLED", policy.decide(7L, false).failureCode());
        assertEquals("V2_PROJECT_POLICY_AMBIGUOUS", policy.decide(7L, false).failureCode());
    }

    @Test
    void onlyEnabledExactPolicyWithPositiveRevisionIsAllowed() {
        properties.setMutationsEnabled(true);
        properties.setSessionBindingEnabled(true);
        when(globalRepository.findById(DevelopmentChangePolicy.CAPABILITY))
                .thenReturn(Optional.of(global(true, 4)));
        when(projectRepository.findByProjectIdAndCapability(
                7L, DevelopmentChangePolicy.CAPABILITY))
                .thenReturn(Optional.of(project(true, 9)));

        var decision = policy.decide(7L, true);

        assertTrue(decision.allowed());
        assertEquals(9, decision.projectPolicyRevision());
    }

    @Test
    void workspaceOperationsAndRestartReconciliationHaveIndependentClosedFlags() {
        properties.setMutationsEnabled(true);

        assertEquals("DEVELOPMENT_CHANGE_WORKSPACE_OPERATIONS_DISABLED",
                policy.decideWorkspace(7L, false).failureCode());

        properties.setWorkspaceOperationsEnabled(true);
        assertEquals("DEVELOPMENT_CHANGE_WORKSPACE_RECONCILIATION_DISABLED",
                policy.decideWorkspace(7L, true).failureCode());
    }

    private V2GlobalCapabilityGateEntity global(boolean enabled, long revision) {
        V2GlobalCapabilityGateEntity value = new V2GlobalCapabilityGateEntity();
        value.setCapability(DevelopmentChangePolicy.CAPABILITY);
        value.setEnabled(enabled);
        value.setRevision(revision);
        return value;
    }

    private V2ProjectCapabilityPolicyEntity project(boolean enabled, long revision) {
        V2ProjectCapabilityPolicyEntity value = new V2ProjectCapabilityPolicyEntity();
        value.setProjectId(7L);
        value.setCapability(DevelopmentChangePolicy.CAPABILITY);
        value.setEnabled(enabled);
        value.setPolicyRevision(revision);
        return value;
    }
}
