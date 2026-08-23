package com.atenea.v2.control;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "atenea.v2")
public class V2ControlProperties {

    private boolean globalEnabled;
    private Map<String, Long> projectPolicyRevisions = new LinkedHashMap<>();

    public boolean isGlobalEnabled() {
        return globalEnabled;
    }

    public void setGlobalEnabled(boolean value) {
        globalEnabled = value;
    }

    public Map<String, Long> getProjectPolicyRevisions() {
        return Map.copyOf(projectPolicyRevisions);
    }

    public void setProjectPolicyRevisions(Map<String, Long> values) {
        projectPolicyRevisions = values == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(values);
    }

    public V2CapabilityPolicy policy() {
        return new V2CapabilityPolicy(globalEnabled, projectPolicyRevisions);
    }
}
