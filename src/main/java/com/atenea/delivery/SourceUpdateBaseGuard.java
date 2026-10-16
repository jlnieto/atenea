package com.atenea.delivery;

import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.github.GitHubRepositoryRef;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Read-only preflight, not authority to choose a new target or to publish. */
@Service
public class SourceUpdateBaseGuard {
    private static final GitHubRepositoryRef APP = new GitHubRepositoryRef("jlnieto", "atenea");
    static final Set<String> PREPARED = Set.of("RESOLVER_COMPLETED", "READY_TO_FINALIZE");
    public enum Status { NOT_REQUIRED, CURRENT, UPDATE_REQUIRED, UNAVAILABLE }

    private final SourceUpdateStore store;
    private final GitHubClient github;

    public SourceUpdateBaseGuard(SourceUpdateStore store, GitHubClient github) {
        this.store = store;
        this.github = github;
    }

    public Status observe(SourceUpdateOperation operation) {
        if (operation == null || !PREPARED.contains(operation.state())
                || store.hasRetainedFinalization(operation.id())) return Status.NOT_REQUIRED;
        // A retained finalization must be inspected/recovered with its original
        // identity. Never offer another preparation for an uncertain effect.
        try {
            String main = github.canonicalMain(APP);
            if (main == null || !main.matches("[0-9a-f]{40}")) return Status.UNAVAILABLE;
            return main.equals(operation.command().targetMainCommit()) ? Status.CURRENT : Status.UPDATE_REQUIRED;
        } catch (GitHubIntegrationException unavailable) {
            return Status.UNAVAILABLE;
        }
    }

    public void requireCurrent(Long sessionId) {
        Status status = observe(store.latest(sessionId).orElse(null));
        if (status == Status.UPDATE_REQUIRED) throw new DeliveryRejectedException("SOURCE_FINALIZATION_MAIN_MOVED");
        if (status == Status.UNAVAILABLE) throw new DeliveryRejectedException("SOURCE_BASE_OBSERVATION_UNAVAILABLE");
    }
}
