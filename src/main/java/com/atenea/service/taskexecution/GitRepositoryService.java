package com.atenea.service.taskexecution;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GitRepositoryService {

    public String getCurrentBranch(String repoPath) {
        return runAndRead(repoPath, List.of("git", "rev-parse", "--abbrev-ref", "HEAD"));
    }

    public boolean isWorkingTreeClean(String repoPath) {
        return runAndRead(repoPath, List.of("git", "status", "--porcelain")).isBlank();
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
            throw new TaskLaunchBlockedException(failurePrefix + ": " + summarize(result.stderr()));
        }
    }

    private String runAndRead(String repoPath, List<String> command) {
        CommandResult result = execute(repoPath, command);
        if (result.exitCode() != 0) {
            throw new TaskLaunchBlockedException("Git command failed: " + String.join(" ", command)
                    + ": " + summarize(result.stderr()));
        }
        return result.stdout().trim();
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
            throw new TaskLaunchBlockedException("Git command interrupted");
        } catch (Exception exception) {
            throw new TaskLaunchBlockedException("Failed to inspect git repository: " + exception.getMessage());
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
