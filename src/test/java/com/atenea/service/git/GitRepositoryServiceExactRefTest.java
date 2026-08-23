package com.atenea.service.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryServiceExactRefTest {

    @TempDir Path temporaryDirectory;

    private final GitRepositoryService service = new GitRepositoryService();

    @Test
    void resolvesOnlyExactHeadCommitAndTree() throws Exception {
        Path repository = initializedRepository();

        String commit = service.resolveExactHeadCommit(
                repository.toString(), "refs/heads/main");
        String tree = service.resolveCommitTree(repository.toString(), commit);

        assertEquals(run(repository, "git", "rev-parse", "HEAD"), commit);
        assertEquals(run(repository, "git", "rev-parse", "HEAD^{tree}"), tree);
        assertFalse(service.exactLocalHeadExists(
                repository.toString(), "refs/heads/atenea/change-missing"));
    }

    @Test
    void distinguishesPresentAbsentInvalidAndUnavailableBranchState() throws Exception {
        Path repository = initializedRepository();
        run(repository, "git", "branch", "atenea/change-present");

        assertTrue(service.exactLocalHeadExists(
                repository.toString(), "refs/heads/atenea/change-present"));
        assertFalse(service.exactLocalHeadExists(
                repository.toString(), "refs/heads/atenea/change-absent"));
        assertThrows(GitRepositoryOperationException.class,
                () -> service.exactLocalHeadExists(repository.toString(), "main"));
        assertThrows(GitRepositoryOperationException.class,
                () -> service.exactLocalHeadExists(
                        temporaryDirectory.resolve("not-a-repository").toString(),
                        "refs/heads/atenea/change-absent"));
    }

    private Path initializedRepository() throws Exception {
        Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
        run(repository, "git", "init", "--initial-branch=main");
        run(repository, "git", "config", "user.name", "Synthetic M2 Test");
        run(repository, "git", "config", "user.email", "synthetic-m2@atenea.test");
        Files.writeString(repository.resolve("fixture.txt"), "synthetic\n",
                StandardCharsets.UTF_8);
        run(repository, "git", "add", "fixture.txt");
        run(repository, "git", "commit", "-m", "synthetic fixture");
        return repository;
    }

    private String run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(List.of(command))
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("Synthetic git command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Synthetic git command failed: " + output);
        }
        return output;
    }
}
