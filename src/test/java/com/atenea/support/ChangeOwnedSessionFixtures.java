package com.atenea.support;

import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import java.time.Instant;
import java.util.UUID;

/** Valid change-owned persistence fixtures, never a production legacy adoption. */
public final class ChangeOwnedSessionFixtures {
    private static final String TITLE = "Synthetic change-owned persistence fixture";

    private ChangeOwnedSessionFixtures() {}

    public static WorkSessionEntity bind(WorkSessionEntity session, DevelopmentChangeRepository changes) {
        requireTestDatabase();
        if (session.getDevelopmentChange() != null) {
            return session;
        }
        DevelopmentChangeEntity change = new DevelopmentChangeEntity();
        UUID key = UUID.randomUUID();
        String worker = session.getSelectedWorkerId() == null ? "synthetic-fixture-worker" : session.getSelectedWorkerId();
        Instant now = session.getCreatedAt() == null ? Instant.now() : session.getCreatedAt();
        change.setChangeKey(key);
        change.setProject(session.getProject());
        change.setTitle(TITLE);
        change.setBaseRef("refs/heads/" + session.getBaseBranch());
        change.setBaseCommit(session.getCanonicalSourceCommit() == null ? "1".repeat(40) : session.getCanonicalSourceCommit());
        change.setObservedCanonicalCommit(change.getBaseCommit());
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        change.setWorkspaceOperationRevision(1);
        change.setWorkspaceObservationSha256("b".repeat(64));
        change.setWorkspaceUpdatedAt(now);
        change.setWorkspaceBranch("atenea/change-" + key);
        change.setWorkspaceIdentity("remote:" + worker + ":change:" + key);
        change.setSelectedWorkerId(worker);
        change.setProjectPolicyRevision(1);
        change.setSourceFingerprintSha256("a".repeat(64));
        change.setCreatedAt(now);
        change.setUpdatedAt(now);
        session.setDevelopmentChange(changes.saveAndFlush(change));
        session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setWorkspaceIdentity(change.getWorkspaceIdentity());
        return session;
    }

    public static void prune(DevelopmentChangeRepository changes) {
        requireTestDatabase();
        changes.findAll().stream().filter(c -> TITLE.equals(c.getTitle())).forEach(changes::delete);
        changes.flush();
    }

    private static void requireTestDatabase() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || !url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|db-test):[0-9]+/atenea_test")
                || !"atenea".equals(System.getenv("SPRING_DATASOURCE_USERNAME"))
                || !"atenea".equals(System.getenv("SPRING_DATASOURCE_PASSWORD"))) {
            throw new IllegalStateException("Synthetic session fixtures require the isolated test database");
        }
    }
}
