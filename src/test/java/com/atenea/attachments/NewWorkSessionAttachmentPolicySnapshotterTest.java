package com.atenea.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.remoteworker.ProjectCodexIdentity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NewWorkSessionAttachmentPolicySnapshotterTest {

    @Test
    void snapshotsRevisionOnlyForNewExactRemoteAteneaOwnership() {
        NewWorkSessionAttachmentPolicySnapshotter snapshotter = snapshotter(true, Set.of("atenea"));
        WorkSessionEntity session = exactRemoteAteneaSession();

        snapshotter.snapshotNewSession(session);

        assertEquals(RealAttachmentProjectRegistry.ATENEA_POLICY_REVISION,
                session.getAttachmentPolicyRevision());
    }

    @Test
    void globalOrProjectDisableLeavesRevisionNull() {
        WorkSessionEntity globallyDisabled = exactRemoteAteneaSession();
        WorkSessionEntity projectDisabled = exactRemoteAteneaSession();

        snapshotter(false, Set.of("atenea")).snapshotNewSession(globallyDisabled);
        snapshotter(true, Set.of()).snapshotNewSession(projectDisabled);

        assertNull(globallyDisabled.getAttachmentPolicyRevision());
        assertNull(projectDisabled.getAttachmentPolicyRevision());
    }

    @Test
    void exactLocalAteneaSessionRemainsIneligible() {
        WorkSessionEntity session = exactRemoteAteneaSession();
        session.setExecutionTarget(ExecutionTarget.LOCAL);
        session.setSelectedWorkerId(null);
        session.setRemoteSessionId(null);
        session.setRemoteWorkloadKind(null);
        session.setWorkspaceIdentity("local:work-session:41");

        snapshotter(true, Set.of("atenea")).snapshotNewSession(session);

        assertNull(session.getAttachmentPolicyRevision());
    }

    @Test
    void foreignOrIncompleteRemoteOwnershipRemainsIneligible() {
        WorkSessionEntity foreignProject = exactRemoteAteneaSession();
        foreignProject.getProject().setName("Beautips");
        WorkSessionEntity foreignWorker = exactRemoteAteneaSession();
        foreignWorker.setSelectedWorkerId("foreign-worker");
        WorkSessionEntity missingRemoteId = exactRemoteAteneaSession();
        missingRemoteId.setRemoteSessionId(null);
        WorkSessionEntity foreignWorkload = exactRemoteAteneaSession();
        foreignWorkload.setRemoteWorkloadKind("synthetic-routing-v1");
        WorkSessionEntity ambiguousWorkspace = exactRemoteAteneaSession();
        ambiguousWorkspace.setWorkspaceIdentity("remote:ax42-01:work-session:" + UUID.randomUUID());

        NewWorkSessionAttachmentPolicySnapshotter snapshotter = snapshotter(true, Set.of("atenea"));
        for (WorkSessionEntity session : List.of(
                foreignProject, foreignWorker, missingRemoteId, foreignWorkload, ambiguousWorkspace)) {
            snapshotter.snapshotNewSession(session);
            assertNull(session.getAttachmentPolicyRevision());
        }
    }

    @Test
    void existingRevisionIsImmutableEvenWhenCurrentOwnershipIsForeign() {
        WorkSessionEntity session = exactRemoteAteneaSession();
        session.setAttachmentPolicyRevision(RealAttachmentProjectRegistry.ATENEA_POLICY_REVISION);
        session.setSelectedWorkerId("foreign-worker");

        snapshotter(true, Set.of("atenea")).snapshotNewSession(session);

        assertEquals(RealAttachmentProjectRegistry.ATENEA_POLICY_REVISION,
                session.getAttachmentPolicyRevision());
    }

    private NewWorkSessionAttachmentPolicySnapshotter snapshotter(boolean enabled, Set<String> projects) {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setEnabled(enabled);
        properties.setRealProjectAllowlist(projects);
        RealAttachmentProjectRegistry registry = new RealAttachmentProjectRegistry(properties);
        return new NewWorkSessionAttachmentPolicySnapshotter(
                new AttachmentAdmissionPolicy(properties, registry),
                registry);
    }

    private WorkSessionEntity exactRemoteAteneaSession() {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);

        UUID remoteSessionId = UUID.randomUUID();
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setProject(project);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(RealAttachmentProjectRegistry.ATENEA_WORKER_ID);
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceIdentity(
                "remote:" + RealAttachmentProjectRegistry.ATENEA_WORKER_ID + ":work-session:" + remoteSessionId);
        return session;
    }
}
