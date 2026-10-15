package com.atenea.attachments;

import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.remoteworker.ProjectCodexIdentity;
import java.util.Objects;

/** One canonical check shared by admission, indexing, downloads and dispatch. */
public final class AttachmentOwnership {
    public static final String CHANGE_POLICY = "atenea-change-attachments-v1";
    private AttachmentOwnership() { }

    public static boolean changeOwned(WorkSessionEntity session) {
        if (session == null || session.getDevelopmentChange() == null || session.getProject() == null) return false;
        var change = session.getDevelopmentChange();
        return Long.valueOf(1).equals(session.getProject().getId()) && change.getChangeKey() != null && change.getProject() != null
                && Objects.equals(session.getProject().getId(), change.getProject().getId())
                && Objects.equals(session.getSelectedWorkerId(), change.getSelectedWorkerId())
                && Objects.equals(session.getWorkspaceIdentity(), change.getWorkspaceIdentity())
                && Objects.equals(session.getWorkspaceBranch(), change.getWorkspaceBranch())
                && ("remote:ax42-01:change:" + change.getChangeKey()).equals(session.getWorkspaceIdentity())
                && ("atenea/change-" + change.getChangeKey()).equals(session.getWorkspaceBranch());
    }

    public static boolean exact(WorkSessionEntity session) {
        if (session == null || !ProjectCodexIdentity.matches(session)
                || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !"ax42-01".equals(session.getSelectedWorkerId()) || session.getRemoteSessionId() == null
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())) return false;
        if (CHANGE_POLICY.equals(session.getAttachmentPolicyRevision())) return changeOwned(session);
        return RealAttachmentProjectRegistry.ATENEA_POLICY_REVISION.equals(session.getAttachmentPolicyRevision())
                && session.getDevelopmentChange() == null
                && ("remote:ax42-01:work-session:" + session.getRemoteSessionId()).equals(session.getWorkspaceIdentity());
    }
}
