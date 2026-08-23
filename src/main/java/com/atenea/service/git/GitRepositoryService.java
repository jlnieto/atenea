package com.atenea.service.git;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class GitRepositoryService {

    private static final Pattern EXACT_HEAD_REF =
            Pattern.compile("refs/heads/[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");
    private static final Pattern GIT_OBJECT = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");

    public String getCurrentBranch(String repoPath) {
        return runAndRead(repoPath, List.of("git", "rev-parse", "--abbrev-ref", "HEAD"));
    }

    public boolean isWorkingTreeClean(String repoPath) {
        return runAndRead(repoPath, List.of("git", "status", "--porcelain")).isBlank();
    }

    public List<String> getWorkingTreeStatusEntries(String repoPath) {
        String output = runAndReadAllowingEmpty(repoPath, List.of("git", "status", "--porcelain"));
        if (output.isBlank()) {
            return List.of();
        }
        return Arrays.stream(output.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    public GitRepositoryState inspect(String repoPath, String baseBranch, String taskBranch) {
        String currentBranch = getCurrentBranch(repoPath);
        boolean workingTreeClean = isWorkingTreeClean(repoPath);
        boolean taskBranchExists = run(repoPath, List.of(
                "git", "show-ref", "--verify", "--quiet", "refs/heads/" + taskBranch)) == 0;
        boolean baseBranchUpToDate = isBaseBranchUpToDate(repoPath, baseBranch);
        return new GitRepositoryState(currentBranch, workingTreeClean, baseBranchUpToDate, taskBranchExists);
    }

    public void checkoutBranch(String repoPath, String branchName) {
        runOrThrow(repoPath, List.of("git", "checkout", branchName),
                "Could not checkout task branch '" + branchName + "'");
    }

    public void createAndCheckoutBranch(String repoPath, String baseBranch, String branchName) {
        runOrThrow(repoPath, List.of("git", "checkout", "-b", branchName, baseBranch),
                "Could not create task branch '" + branchName + "' from '" + baseBranch + "'");
    }

    public boolean branchExists(String repoPath, String branchName) {
        return run(repoPath, List.of("git", "show-ref", "--verify", "--quiet", "refs/heads/" + branchName)) == 0;
    }

    public void stageAll(String repoPath) {
        runOrThrow(repoPath, List.of("git", "add", "-A"),
                "Could not stage repository changes");
    }

    public void commit(String repoPath, String message) {
        runOrThrow(repoPath, List.of("git", "commit", "-m", message),
                "Could not commit repository changes");
    }

    public void pushBranchSetUpstream(String repoPath, String branchName) {
        runOrThrow(repoPath, List.of("git", "push", "-u", "origin", branchName),
                "Could not push branch '" + branchName + "' to origin");
    }

    public void fetchOrigin(String repoPath) {
        runOrThrow(repoPath, List.of("git", "fetch", "origin", "--prune"),
                "Could not fetch origin");
    }

    public void fastForwardCurrentBranchToOrigin(String repoPath, String branchName) {
        runOrThrow(repoPath, List.of("git", "merge", "--ff-only", "origin/" + branchName),
                "Could not fast-forward branch '" + branchName + "' to origin/" + branchName);
    }

    public void deleteLocalBranch(String repoPath, String branchName) {
        runOrThrow(repoPath, List.of("git", "branch", "-d", branchName),
                "Could not delete local branch '" + branchName + "'");
    }

    public boolean remoteBranchExists(String repoPath, String branchName) {
        return !runAndReadAllowingEmpty(repoPath, List.of("git", "ls-remote", "--heads", "origin", branchName)).isBlank();
    }

    public String getRemoteBranchHeadSha(String repoPath, String branchName) {
        String output = runAndReadAllowingEmpty(
                repoPath,
                List.of("git", "ls-remote", "--heads", "origin", branchName));
        if (output.isBlank()) {
            return null;
        }
        String[] lines = output.split("\\R");
        String[] fields = lines[0].trim().split("\\s+");
        String expectedRef = "refs/heads/" + branchName;
        if (lines.length != 1
                || fields.length != 2
                || !fields[0].matches("[0-9a-f]{40}")
                || !expectedRef.equals(fields[1])) {
            throw new GitRepositoryOperationException(
                    "Remote branch '" + branchName + "' returned ambiguous ownership");
        }
        return fields[0];
    }

    public void deleteRemoteBranch(String repoPath, String branchName) {
        runOrThrow(repoPath, List.of("git", "push", "origin", "--delete", branchName),
                "Could not delete remote branch '" + branchName + "'");
    }

    public boolean branchContainsCommitsBeyond(String repoPath, String baseBranch, String branchName) {
        if (!branchExists(repoPath, branchName)) {
            return false;
        }
        String count = runAndRead(repoPath, List.of("git", "rev-list", "--count", baseBranch + ".." + branchName));
        return !"0".equals(count);
    }

    public String getHeadCommitSha(String repoPath) {
        return runAndRead(repoPath, List.of("git", "rev-parse", "HEAD"));
    }

    public String resolveExactHeadCommit(String repoPath, String exactHeadRef) {
        if (exactHeadRef == null || !EXACT_HEAD_REF.matcher(exactHeadRef).matches()
                || exactHeadRef.contains("..") || exactHeadRef.contains("//")) {
            throw new GitRepositoryOperationException("Base ref is not an exact server-owned head ref");
        }
        String commit = runAndRead(repoPath, List.of(
                "git", "rev-parse", "--verify", "--end-of-options",
                exactHeadRef + "^{commit}"));
        if (!GIT_OBJECT.matcher(commit).matches()) {
            throw new GitRepositoryOperationException("Base ref did not resolve to one exact commit");
        }
        return commit;
    }

    public String resolveCommitTree(String repoPath, String commit) {
        if (commit == null || !GIT_OBJECT.matcher(commit).matches()) {
            throw new GitRepositoryOperationException("Commit identity is not exact");
        }
        String tree = runAndRead(repoPath, List.of(
                "git", "rev-parse", "--verify", "--end-of-options", commit + "^{tree}"));
        if (!GIT_OBJECT.matcher(tree).matches()) {
            throw new GitRepositoryOperationException("Commit did not resolve to one exact tree");
        }
        return tree;
    }

    public boolean exactLocalHeadExists(String repoPath, String exactHeadRef) {
        if (exactHeadRef == null || !EXACT_HEAD_REF.matcher(exactHeadRef).matches()
                || exactHeadRef.contains("..") || exactHeadRef.contains("//")) {
            throw new GitRepositoryOperationException("Branch ref is not an exact server-owned head ref");
        }
        CommandResult result = execute(repoPath, List.of(
                "git", "show-ref", "--verify", "--quiet", exactHeadRef));
        if (result.exitCode() == 0) {
            return true;
        }
        if (result.exitCode() == 1) {
            return false;
        }
        throw new GitRepositoryOperationException(
                "Could not determine exact branch ownership: " + summarize(result.stderr()));
    }

    public String getOriginRemoteUrl(String repoPath) {
        return runAndRead(repoPath, List.of("git", "remote", "get-url", "origin"));
    }

    public boolean hasReviewableChanges(String repoPath, String baseBranch, String taskBranch) {
        GitRepositoryState state = inspect(repoPath, baseBranch, taskBranch);
        if (!state.taskBranchExists()) {
            return false;
        }

        if (run(repoPath, List.of("git", "diff", "--quiet", baseBranch + ".." + taskBranch)) != 0) {
            return true;
        }

        return state.currentBranch().equals(taskBranch) && !state.workingTreeClean();
    }

    private boolean isBaseBranchUpToDate(String repoPath, String baseBranch) {
        int upstreamExists = run(repoPath, List.of("git", "rev-parse", "--verify", "--quiet", baseBranch + "@{upstream}"));
        if (upstreamExists != 0) {
            return false;
        }

        String divergence = runAndRead(repoPath, List.of("git", "rev-list", "--left-right", "--count",
                baseBranch + "..." + baseBranch + "@{upstream}"));
        String[] counts = divergence.split("\\s+");
        if (counts.length < 2) {
            return false;
        }

        return "0".equals(counts[0]) && "0".equals(counts[1]);
    }

    private void runOrThrow(String repoPath, List<String> command, String failurePrefix) {
        CommandResult result = execute(repoPath, command);
        if (result.exitCode() != 0) {
            throw new GitRepositoryOperationException(failurePrefix + ": " + summarize(result.stderr()));
        }
    }

    private String runAndRead(String repoPath, List<String> command) {
        CommandResult result = execute(repoPath, command);
        if (result.exitCode() != 0) {
            throw new GitRepositoryOperationException("Git command failed: " + String.join(" ", command)
                    + ": " + summarize(result.stderr()));
        }
        return result.stdout().trim();
    }

    private String runAndReadAllowingEmpty(String repoPath, List<String> command) {
        CommandResult result = execute(repoPath, command);
        if (result.exitCode() != 0) {
            throw new GitRepositoryOperationException("Git command failed: " + String.join(" ", command)
                    + ": " + summarize(result.stderr()));
        }
        return result.stdout() == null ? "" : result.stdout().trim();
    }

    private int run(String repoPath, List<String> command) {
        return execute(repoPath, command).exitCode();
    }

    private CommandResult execute(String repoPath, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(Path.of(repoPath).toFile())
                    .start();

            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                stdout = reader.lines().reduce("", (left, right) -> left + System.lineSeparator() + right).trim();
            }

            String stderr;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                stderr = reader.lines().reduce("", (left, right) -> left + System.lineSeparator() + right).trim();
            }

            int exitCode = process.waitFor();
            return new CommandResult(exitCode, stdout, stderr);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitRepositoryOperationException("Git command interrupted");
        } catch (Exception exception) {
            throw new GitRepositoryOperationException("Failed to inspect git repository: " + exception.getMessage());
        }
    }

    private static String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown git error";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
