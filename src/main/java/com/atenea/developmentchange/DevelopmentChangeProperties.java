package com.atenea.developmentchange;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "atenea.development-change")
public class DevelopmentChangeProperties {

    private boolean mutationsEnabled;
    private boolean sessionBindingEnabled;
    private boolean workspaceOperationsEnabled;
    private boolean workspaceReconciliationEnabled;

    public boolean isMutationsEnabled() {
        return mutationsEnabled;
    }

    public void setMutationsEnabled(boolean value) {
        mutationsEnabled = value;
    }

    public boolean isSessionBindingEnabled() {
        return sessionBindingEnabled;
    }

    public void setSessionBindingEnabled(boolean value) {
        sessionBindingEnabled = value;
    }

    public boolean isWorkspaceOperationsEnabled() {
        return workspaceOperationsEnabled;
    }

    public void setWorkspaceOperationsEnabled(boolean value) {
        workspaceOperationsEnabled = value;
    }

    public boolean isWorkspaceReconciliationEnabled() {
        return workspaceReconciliationEnabled;
    }

    public void setWorkspaceReconciliationEnabled(boolean value) {
        workspaceReconciliationEnabled = value;
    }
}
