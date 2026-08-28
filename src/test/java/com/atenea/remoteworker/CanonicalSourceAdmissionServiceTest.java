package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.WorkSessionOperationBlockedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalSourceAdmissionServiceTest {

    @TempDir
    Path temporary;

    private Path origin;
    private Path repository;
    private WorkSessionRepository workSessionRepository;
    private CanonicalSourceAdmissionService service;
    private WorkSessionEntity session;

    @BeforeEach
    void setUp() throws Exception {
        origin = temporary.resolve("origin.git");
        repository = temporary.resolve("atenea");
        git(temporary, "init", "--bare", origin.toString());
        git(temporary, "init", "-b", ProjectCodexIdentity.BRANCH, repository.toString());
        git(repository, "config", "user.name", "Canonical source test");
        git(repository, "config", "user.email", "canonical-source@example.invalid");
        git(repository, "remote", "add", "origin", origin.toString());
        commitAndPush("first");

        workSessionRepository = mock(WorkSessionRepository.class);
        service = new CanonicalSourceAdmissionService(
                workSessionRepository,
                repository,
                origin.toString(),
                ProjectCodexIdentity.BRANCH);
        session = remoteAteneaSession();
    }

    @Test
    void cleanExactHeadIsObservedAndPersisted() {
        service.admitBeforeWrite(session);

        assertEquals(git(repository, "rev-parse", "HEAD"), session.getCanonicalSourceCommit());
        assertEquals(
                "refs/heads/" + ProjectCodexIdentity.BRANCH,
                session.getCanonicalSourceRef());
        assertNotNull(session.getCanonicalSourceObservedAt());
        assertEquals(64, session.getCanonicalSourceObservationSha256().length());
        verify(workSessionRepository).save(session);
    }

    @Test
    void canonicalObservationReturnsExactlyTheRemoteTip() {
        CanonicalSourceAdmissionService.CanonicalSourceObservation observation =
                service.observeCanonicalSource(session.getProject());

        assertEquals(origin.toString(), observation.repositoryUrl());
        assertEquals("refs/heads/main", observation.ref());
        assertEquals(git(origin, "rev-parse", "refs/heads/main"), observation.commit());
    }

    @Test
    void changeBaseObservationDependsOnlyOnTheExactRemoteRef() throws Exception {
        String remoteTip = git(origin, "rev-parse", "refs/heads/main");
        git(repository, "checkout", "--detach");
        Files.writeString(repository.resolve("local-only.txt"), "dirty local checkout");

        CanonicalSourceAdmissionService.CanonicalSourceObservation observation =
                service.observeRemoteBase(session.getProject());

        assertEquals(remoteTip, observation.commit());
        assertEquals("refs/heads/main", observation.ref());
    }

    @Test
    void staleHeadIsRejectedEvenWhenItIsAnAncestor() throws Exception {
        Path publisher = temporary.resolve("publisher");
        git(temporary, "clone", "--branch", ProjectCodexIdentity.BRANCH,
                origin.toString(), publisher.toString());
        git(publisher, "config", "user.name", "Canonical source test");
        git(publisher, "config", "user.email", "canonical-source@example.invalid");
        Files.writeString(publisher.resolve("source.txt"), "second");
        git(publisher, "add", "source.txt");
        git(publisher, "commit", "-m", "second");
        git(publisher, "push", "origin", ProjectCodexIdentity.BRANCH);

        assertThrows(WorkSessionOperationBlockedException.class, () -> service.admitBeforeWrite(session));
    }

    @Test
    void dirtyTrackedOrUntrackedSourceIsRejected() throws Exception {
        Files.writeString(repository.resolve("untracked.txt"), "draft");

        assertThrows(WorkSessionOperationBlockedException.class, () -> service.admitBeforeWrite(session));
    }

    @Test
    void legacyPlatformRemoteIsRejected() {
        git(repository, "remote", "set-url", "origin",
                "https://github.com/jlnieto/atenea-remote-worker-spec.git");

        assertThrows(WorkSessionOperationBlockedException.class,
                () -> service.observeCanonicalSource(session.getProject()));
    }

    @Test
    void anyOtherRemoteIsRejected() {
        git(repository, "remote", "set-url", "origin",
                "https://github.com/another/repository.git");

        assertThrows(WorkSessionOperationBlockedException.class,
                () -> service.observeCanonicalSource(session.getProject()));
    }

    @Test
    void missingLocalBranchAndRefAreRejected() {
        git(repository, "checkout", "--detach");
        git(repository, "branch", "-D", ProjectCodexIdentity.BRANCH);

        assertThrows(WorkSessionOperationBlockedException.class,
                () -> service.observeCanonicalSource(session.getProject()));
    }

    @Test
    void divergedLocalHeadIsRejected() throws Exception {
        Files.writeString(repository.resolve("source.txt"), "diverged");
        git(repository, "add", "source.txt");
        git(repository, "commit", "-m", "diverged");

        assertThrows(WorkSessionOperationBlockedException.class, () -> service.admitBeforeWrite(session));
    }

    @Test
    void legacySessionFixedAtARejectsWhenCanonicalMainAdvancesToB() throws Exception {
        service.admitBeforeWrite(session);
        commitAndPush("second");

        assertThrows(WorkSessionOperationBlockedException.class, () -> service.admitBeforeWrite(session));
    }

    @Test
    void missingOrAmbiguousConfiguredRefFailsClosed() {
        CanonicalSourceAdmissionService missing = new CanonicalSourceAdmissionService(
                mock(WorkSessionRepository.class),
                repository,
                origin.toString(),
                "missing-branch");

        assertThrows(WorkSessionOperationBlockedException.class, () -> missing.admitBeforeWrite(session));
    }

    @Test
    void emptyRemoteObservationFailsClosed() {
        git(repository, "push", "origin", ":refs/heads/" + ProjectCodexIdentity.BRANCH);

        assertThrows(WorkSessionOperationBlockedException.class,
                () -> service.observeCanonicalSource(session.getProject()));
    }

    @Test
    void ambiguousRemoteObservationFailsClosed() {
        String commit = git(repository, "rev-parse", "HEAD");
        String canonicalRef = "refs/heads/" + ProjectCodexIdentity.BRANCH;
        String ambiguous = commit + "\t" + canonicalRef + "\n"
                + commit + "\t" + canonicalRef;

        assertThrows(WorkSessionOperationBlockedException.class,
                () -> service.exactRemoteCommit(ambiguous, canonicalRef));
    }

    @Test
    void nonCanonicalProjectIdentityFailsClosed() {
        ProjectEntity foreign = new ProjectEntity();
        foreign.setName("Not Atenea");
        foreign.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        foreign.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);

        assertThrows(WorkSessionOperationBlockedException.class,
                () -> service.observeCanonicalSource(foreign));
    }

    private WorkSessionEntity remoteAteneaSession() {
        ProjectEntity project = new ProjectEntity();
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        project.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
        WorkSessionEntity value = new WorkSessionEntity();
        value.setProject(project);
        value.setBaseBranch(ProjectCodexIdentity.BRANCH);
        value.setExecutionTarget(ExecutionTarget.REMOTE);
        return value;
    }

    private void commitAndPush(String contents) throws Exception {
        Files.writeString(repository.resolve("source.txt"), contents);
        git(repository, "add", "source.txt");
        git(repository, "commit", "-m", contents);
        git(repository, "push", "-u", "origin", ProjectCodexIdentity.BRANCH);
    }

    private String git(Path directory, String... arguments) {
        try {
            List<String> command = new java.util.ArrayList<>(List.of("git", "-C", directory.toString()));
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (process.waitFor() != 0) {
                throw new IllegalStateException(output);
            }
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
