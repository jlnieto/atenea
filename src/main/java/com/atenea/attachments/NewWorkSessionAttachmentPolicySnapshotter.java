package com.atenea.attachments;

import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.remoteworker.ProjectCodexIdentity;
import org.springframework.stereotype.Component;

@Component
public final class NewWorkSessionAttachmentPolicySnapshotter {

    private final AttachmentAdmissionPolicy admissionPolicy;
    private final RealAttachmentProjectRegistry projectRegistry;

    public NewWorkSessionAttachmentPolicySnapshotter(
            AttachmentAdmissionPolicy admissionPolicy,
            RealAttachmentProjectRegistry projectRegistry
    ) {
        this.admissionPolicy = admissionPolicy;
        this.projectRegistry = projectRegistry;
    }

    public void snapshotNewSession(WorkSessionEntity session) {
        if (session == null || session.getAttachmentPolicyRevision() != null) {
            return;
        }

        RealAttachmentProjectRegistry.CanonicalProject project = projectRegistry.requireRegistered(
                ProjectCodexIdentity.PROJECT_IDENTITY);
        if (!admissionPolicy.isRealCreateBindEnabled(project.projectIdentity())
                || !hasExactRemoteOwnership(session, project)) {
            return;
        }

        session.setAttachmentPolicyRevision(project.policyRevision());
    }

    private boolean hasExactRemoteOwnership(
            WorkSessionEntity session,
            RealAttachmentProjectRegistry.CanonicalProject project
    ) {
        return ProjectCodexIdentity.matches(session)
                && session.getExecutionTarget() == ExecutionTarget.REMOTE
                && project.workerId().equals(session.getSelectedWorkerId())
                && session.getRemoteSessionId() != null
                && ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                && ("remote:" + project.workerId() + ":work-session:" + session.getRemoteSessionId())
                        .equals(session.getWorkspaceIdentity());
    }
}
