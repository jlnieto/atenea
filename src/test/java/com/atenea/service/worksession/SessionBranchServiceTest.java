package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.service.git.GitRepositoryService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionBranchServiceTest {

    private final SessionBranchService service =
            new SessionBranchService(mock(GitRepositoryService.class));

    @Test
    void remoteSessionBranchUsesPersistedExternalUuid() {
        UUID remoteSessionId = UUID.fromString("4bb26a65-0a0a-4ae0-b8e0-b41e03a695bf");
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setRemoteSessionId(remoteSessionId);

        assertEquals(
                "atenea/session-" + remoteSessionId,
                service.resolveWorkspaceBranch(session));
    }

    @Test
    void localSessionBranchRetainsNumericCompatibility() {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setExecutionTarget(ExecutionTarget.LOCAL);

        assertEquals("atenea/session-41", service.resolveWorkspaceBranch(session));
    }

    @Test
    void remoteSessionWithoutExternalIdentityFailsClosed() {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setExecutionTarget(ExecutionTarget.REMOTE);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveWorkspaceBranch(session));
    }
}
