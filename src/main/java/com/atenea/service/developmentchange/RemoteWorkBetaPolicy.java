package com.atenea.service.developmentchange;

import com.atenea.developmentchange.RemoteWorkBetaProperties;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateRepository;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyRepository;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;

@Service
public class RemoteWorkBetaPolicy {

    public static final String CAPABILITY = "atenea-v2-remote-work-beta";

    private final RemoteWorkBetaProperties properties;
    private final V2GlobalCapabilityGateRepository globalGateRepository;
    private final V2ProjectCapabilityPolicyRepository projectPolicyRepository;

    public RemoteWorkBetaPolicy(
            RemoteWorkBetaProperties properties,
            V2GlobalCapabilityGateRepository globalGateRepository,
            V2ProjectCapabilityPolicyRepository projectPolicyRepository) {
        this.properties = properties;
        this.globalGateRepository = globalGateRepository;
        this.projectPolicyRepository = projectPolicyRepository;
    }

    public Decision decide(Long projectId) {
        if (projectId == null || projectId <= 0) {
            return Decision.denied("REMOTE_WORK_BETA_PROJECT_INVALID");
        }
        if (!properties.isOpenOrResolveEnabled()) {
            return Decision.denied("REMOTE_WORK_BETA_OPEN_OR_RESOLVE_DISABLED");
        }
        var global = globalGateRepository.findById(CAPABILITY).orElse(null);
        if (global == null || !global.isEnabled()) {
            return Decision.denied("REMOTE_WORK_BETA_GLOBAL_GATE_DISABLED");
        }
        if (global.getRevision() <= 0) {
            return Decision.denied("REMOTE_WORK_BETA_GLOBAL_GATE_REVISION_INVALID");
        }
        try {
            var project = projectPolicyRepository
                    .findByProjectIdAndCapability(projectId, CAPABILITY)
                    .orElse(null);
            if (project == null || !project.isEnabled()) {
                return Decision.denied("REMOTE_WORK_BETA_PROJECT_POLICY_DISABLED");
            }
            if (project.getPolicyRevision() <= 0) {
                return Decision.denied("REMOTE_WORK_BETA_PROJECT_POLICY_REVISION_INVALID");
            }
            return Decision.allowed(project.getPolicyRevision());
        } catch (IncorrectResultSizeDataAccessException ambiguous) {
            return Decision.denied("REMOTE_WORK_BETA_PROJECT_POLICY_AMBIGUOUS");
        }
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
