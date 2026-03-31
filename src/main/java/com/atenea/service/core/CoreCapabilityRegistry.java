package com.atenea.service.core;

import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreRiskLevel;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CoreCapabilityRegistry {

    private final Map<String, CapabilityDefinition> definitions = Map.ofEntries(
            Map.entry(key(CoreDomain.DEVELOPMENT, "list_projects_overview"),
                    new CapabilityDefinition(CoreDomain.DEVELOPMENT, "list_projects_overview", CoreRiskLevel.READ, false, true)),
            Map.entry(key(CoreDomain.DEVELOPMENT, "get_project_overview"),
                    new CapabilityDefinition(CoreDomain.DEVELOPMENT, "get_project_overview", CoreRiskLevel.READ, false, true)),
            Map.entry(key(CoreDomain.DEVELOPMENT, "activate_project_context"),
                    new CapabilityDefinition(CoreDomain.DEVELOPMENT, "activate_project_context", CoreRiskLevel.READ, false, true)),
            Map.entry(key(CoreDomain.DEVELOPMENT, "create_work_session"),
                    new CapabilityDefinition(CoreDomain.DEVELOPMENT, "create_work_session", CoreRiskLevel.READ, false, true)),
            Map.entry(key(CoreDomain.DEVELOPMENT, "continue_work_session"),
                    new CapabilityDefinition(CoreDomain.DEVELOPMENT, "continue_work_session", CoreRiskLevel.READ, false, true)),
            Map.entry(key(CoreDomain.DEVELOPMENT, "publish_work_session"),
                    new CapabilityDefinition(CoreDomain.DEVELOPMENT, "publish_work_session", CoreRiskLevel.SAFE_WRITE, true, true)),
            Map.entry(key(CoreDomain.DEVELOPMENT, "sync_work_session_pull_request"),
                    new CapabilityDefinition(CoreDomain.DEVELOPMENT, "sync_work_session_pull_request", CoreRiskLevel.READ, false, true)),
            Map.entry(key(CoreDomain.DEVELOPMENT, "get_session_summary"),
                    new CapabilityDefinition(CoreDomain.DEVELOPMENT, "get_session_summary", CoreRiskLevel.READ, false, true)),
            Map.entry(key(CoreDomain.DEVELOPMENT, "get_session_deliverables"),
                    new CapabilityDefinition(CoreDomain.DEVELOPMENT, "get_session_deliverables", CoreRiskLevel.READ, false, true)),
            Map.entry(key(CoreDomain.DEVELOPMENT, "generate_session_deliverable"),
                    new CapabilityDefinition(CoreDomain.DEVELOPMENT, "generate_session_deliverable", CoreRiskLevel.SAFE_WRITE, true, true)),
            Map.entry(key(CoreDomain.DEVELOPMENT, "close_work_session"),
                    new CapabilityDefinition(CoreDomain.DEVELOPMENT, "close_work_session", CoreRiskLevel.SAFE_WRITE, true, true)),
            Map.entry(key(CoreDomain.OPERATIONS, "check_service"),
                    new CapabilityDefinition(CoreDomain.OPERATIONS, "check_service", CoreRiskLevel.READ, false, false)),
            Map.entry(key(CoreDomain.OPERATIONS, "restart_service"),
                    new CapabilityDefinition(CoreDomain.OPERATIONS, "restart_service", CoreRiskLevel.SAFE_WRITE, true, false)),
            Map.entry(key(CoreDomain.COMMUNICATIONS, "read_latest_email"),
                    new CapabilityDefinition(CoreDomain.COMMUNICATIONS, "read_latest_email", CoreRiskLevel.READ, false, false)),
            Map.entry(key(CoreDomain.COMMUNICATIONS, "draft_email"),
                    new CapabilityDefinition(CoreDomain.COMMUNICATIONS, "draft_email", CoreRiskLevel.READ, false, false)),
            Map.entry(key(CoreDomain.COMMUNICATIONS, "send_email"),
                    new CapabilityDefinition(CoreDomain.COMMUNICATIONS, "send_email", CoreRiskLevel.SAFE_WRITE, true, false))
    );

    public CapabilityDefinition requireEnabled(CoreDomain domain, String capability) {
        CapabilityDefinition definition = definitions.get(key(domain, capability));
        if (definition == null) {
            throw new CoreCapabilityDisabledException("Capability is not registered: " + domain + ":" + capability);
        }
        if (!definition.enabled()) {
            throw new CoreCapabilityDisabledException("Capability is not enabled: " + domain + ":" + capability);
        }
        return definition;
    }

    private static String key(CoreDomain domain, String capability) {
        return domain.name() + ":" + capability;
    }
}
