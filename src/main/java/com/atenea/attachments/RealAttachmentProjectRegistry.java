package com.atenea.attachments;

import com.atenea.remoteworker.ProjectCodexIdentity;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class RealAttachmentProjectRegistry {

    public static final String ATENEA_POLICY_REVISION = "atenea-real-attachments-v1";
    public static final String ATENEA_WORKER_ID = "ax42-01";

    private static final Map<String, CanonicalProject> REGISTERED = Map.of(
            ProjectCodexIdentity.PROJECT_IDENTITY,
            new CanonicalProject(
                    ProjectCodexIdentity.PROJECT_IDENTITY,
                    ATENEA_WORKER_ID,
                    ATENEA_POLICY_REVISION));

    private final Set<String> enabledProjects;

    public RealAttachmentProjectRegistry(AttachmentProperties properties) {
        Set<String> configured = new LinkedHashSet<>(properties.getRealProjectAllowlist());
        Set<String> unknown = new LinkedHashSet<>(configured);
        unknown.removeAll(REGISTERED.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "Unknown real attachment project identities are configured: " + unknown);
        }
        this.enabledProjects = Collections.unmodifiableSet(configured);
    }

    public boolean isRegistered(String projectIdentity) {
        return REGISTERED.containsKey(projectIdentity);
    }

    public boolean isEnabled(String projectIdentity) {
        return enabledProjects.contains(projectIdentity);
    }

    public CanonicalProject requireRegistered(String projectIdentity) {
        CanonicalProject project = REGISTERED.get(projectIdentity);
        if (project == null) {
            throw new IllegalArgumentException("Project is not registered for real attachments");
        }
        return project;
    }

    public Set<String> enabledProjects() {
        return enabledProjects;
    }

    public record CanonicalProject(
            String projectIdentity,
            String workerId,
            String policyRevision
    ) {
    }
}
