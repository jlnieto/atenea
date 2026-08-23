package com.atenea.service.developmentchange;

import com.atenea.developmentchange.DevelopmentChangeProperties;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateRepository;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyRepository;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;

@Service
public class DevelopmentChangePolicy {

    public static final String CAPABILITY = "atenea-v2-development-change-control";

    private final DevelopmentChangeProperties properties;
    private final V2GlobalCapabilityGateRepository globalGateRepository;
    private final V2ProjectCapabilityPolicyRepository projectPolicyRepository;

    public DevelopmentChangePolicy(
            DevelopmentChangeProperties properties,
            V2GlobalCapabilityGateRepository globalGateRepository,
            V2ProjectCapabilityPolicyRepository projectPolicyRepository) {
        this.properties = properties;
        this.globalGateRepository = globalGateRepository;
        this.projectPolicyRepository = projectPolicyRepository;
    }

    public Decision decide(Long projectId, boolean sessionBinding) {
        if (projectId == null || projectId <= 0) {
            return Decision.denied("DEVELOPMENT_CHANGE_PROJECT_INVALID");
        }
        if (!properties.isMutationsEnabled()) {
            return Decision.denied("DEVELOPMENT_CHANGE_MUTATIONS_DISABLED");
        }
        if (sessionBinding && !properties.isSessionBindingEnabled()) {
            return Decision.denied("DEVELOPMENT_CHANGE_SESSION_BINDING_DISABLED");
        }

        var global = globalGateRepository.findById(CAPABILITY).orElse(null);
        if (global == null || !global.isEnabled()) {
            return Decision.denied("V2_GLOBAL_GATE_DISABLED");
        }
        if (global.getRevision() <= 0) {
            return Decision.denied("V2_GLOBAL_GATE_REVISION_INVALID");
        }

        try {
            var project = projectPolicyRepository
                    .findByProjectIdAndCapability(projectId, CAPABILITY)
                    .orElse(null);
            if (project == null || !project.isEnabled()) {
                return Decision.denied("V2_PROJECT_POLICY_DISABLED");
            }
            if (project.getPolicyRevision() <= 0) {
                return Decision.denied("V2_PROJECT_POLICY_REVISION_INVALID");
            }
            return Decision.allowed(project.getPolicyRevision());
        } catch (IncorrectResultSizeDataAccessException ambiguous) {
            return Decision.denied("V2_PROJECT_POLICY_AMBIGUOUS");
        }
    }

    public Decision decideWorkspace(Long projectId, boolean reconciliation) {
        if (!properties.isWorkspaceOperationsEnabled()) {
            return Decision.denied("DEVELOPMENT_CHANGE_WORKSPACE_OPERATIONS_DISABLED");
        }
        if (reconciliation && !properties.isWorkspaceReconciliationEnabled()) {
            return Decision.denied("DEVELOPMENT_CHANGE_WORKSPACE_RECONCILIATION_DISABLED");
        }
        return decide(projectId, false);
    }

    public record Decision(boolean allowed, long projectPolicyRevision, String failureCode) {

        private static Decision allowed(long revision) {
            return new Decision(true, revision, null);
        }

        private static Decision denied(String code) {
            return new Decision(false, 0, code);
        }
    }
}
