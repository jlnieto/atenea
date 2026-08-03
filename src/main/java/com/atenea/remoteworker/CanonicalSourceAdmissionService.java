package com.atenea.remoteworker;

import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.WorkSessionOperationBlockedException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CanonicalSourceAdmissionService {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    private static final String REF_PREFIX = "refs/heads/";

    private final WorkSessionRepository workSessionRepository;
    private final Path repositoryPath;
    private final String repositoryUrl;
    private final String branch;

    @Autowired
    public CanonicalSourceAdmissionService(WorkSessionRepository workSessionRepository) {
        this(
                workSessionRepository,
                Path.of(ProjectCodexIdentity.REPO_PATH),
                ProjectCodexIdentity.REPOSITORY,
                ProjectCodexIdentity.BRANCH);
    }

    CanonicalSourceAdmissionService(
            WorkSessionRepository workSessionRepository,
            Path repositoryPath,
            String repositoryUrl,
            String branch
    ) {
        this.workSessionRepository = workSessionRepository;
        this.repositoryPath = repositoryPath;
        this.repositoryUrl = repositoryUrl;
        this.branch = branch;
    }

    public void admitBeforeWrite(WorkSessionEntity session) {
        if (session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.matches(session)
                || BeautipsProjectCodexIdentity.matchesPinnedSession(session)) {
            return;
        }

        String canonicalRef = REF_PREFIX + branch;
        String localRemote = run("remote", "get-url", "origin").trim();
        String localBranch = run("symbolic-ref", "--quiet", "--short", "HEAD").trim();
        String localHead = run("rev-parse", "--verify", "HEAD^{commit}").trim();
        String status = run("status", "--porcelain=v1", "-z", "--untracked-files=all");
        String remoteObservation = runExternal(
                "git", "-c", "protocol.file.allow=always",
                "ls-remote", "--exit-code", "--refs", repositoryUrl, canonicalRef).trim();
        String[] fields = remoteObservation.split("\\s+");

        if (!repositoryUrl.equals(localRemote)
                || !branch.equals(localBranch)
                || !status.isEmpty()
                || fields.length != 2
                || !canonicalRef.equals(fields[1])
                || !isCommit(fields[0])
                || !fields[0].equals(localHead)) {
            throw blocked();
        }

        String observedCommit = fields[0];
        if (session.getCanonicalSourceCommit() != null
                && (!observedCommit.equals(session.getCanonicalSourceCommit())
                    || !canonicalRef.equals(session.getCanonicalSourceRef()))) {
            throw blocked();
        }

        Instant observedAt = Instant.now();
        session.setCanonicalSourceRef(canonicalRef);
        session.setCanonicalSourceCommit(observedCommit);
        session.setCanonicalSourceObservationSha256(
                sha256(repositoryUrl + "\0" + canonicalRef + "\0" + observedCommit));
        session.setCanonicalSourceObservedAt(observedAt);
        workSessionRepository.save(session);
    }

    private String run(String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                "git", "-c", "safe.directory=" + repositoryPath,
                "-C", repositoryPath.toString()));
        command.addAll(Arrays.asList(arguments));
        return runExternal(command.toArray(String[]::new));
    }

    private String runExternal(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .directory(repositoryPath.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw blocked();
            }
            byte[] output = process.getInputStream().readNBytes(4097);
            if (process.exitValue() != 0 || output.length > 4096) {
                throw blocked();
            }
            return new String(output, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw blocked();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw blocked();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private boolean isCommit(String value) {
        return value != null && value.matches("^[0-9a-f]{40}$");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private WorkSessionOperationBlockedException blocked() {
        return new WorkSessionOperationBlockedException(
                "Canonical source admission failed closed; create a clean current WorkSession");
    }
}
