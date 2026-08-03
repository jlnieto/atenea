package com.atenea.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.RecoverDraftWorkSessionResponse;
import com.atenea.service.worksession.RetainedDraftRecoveryService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class RetainedDraftRecoveryCommandTest {

    private static final long SESSION_ID = 6L;
    private static final UUID REMOTE_SESSION_ID =
            UUID.fromString("c750641d-3226-44c3-81dc-d9149aac0de1");
    private static final String RETAINED_HEAD = "d5ea39e7b575b63c6fff3a66a0400c5af5e9ff2b";
    private static final String ACCEPTED_COMMIT = "b0307573101159c14e4cd49a2eebdad938dad2bd";

    private final MockEnvironment environment = new MockEnvironment();
    private final RetainedDraftRecoveryService recoveryService = mock(RetainedDraftRecoveryService.class);
    private RetainedDraftRecoveryCommand command;

    @BeforeEach
    void setUp() {
        environment.setProperty("atenea.operations.retained-draft-recovery.session-id", "6");
        environment.setProperty(
                "atenea.operations.retained-draft-recovery.expected-remote-session-id",
                REMOTE_SESSION_ID.toString());
        environment.setProperty(
                "atenea.operations.retained-draft-recovery.expected-retained-head",
                RETAINED_HEAD);
        environment.setProperty(
                "atenea.operations.retained-draft-recovery.expected-accepted-commit",
                ACCEPTED_COMMIT);
        command = new RetainedDraftRecoveryCommand(
                environment,
                recoveryService,
                mock(ConfigurableApplicationContext.class));
    }

    @Test
    void executesOnlyTheExactSanitizedRecovery() {
        when(recoveryService.recoverExact(SESSION_ID, REMOTE_SESSION_ID, RETAINED_HEAD, ACCEPTED_COMMIT))
                .thenReturn(new RecoverDraftWorkSessionResponse(
                SESSION_ID,
                7L,
                RETAINED_HEAD,
                ACCEPTED_COMMIT,
                "a".repeat(64),
                0,
                28,
                16,
                false));

        assertEquals(0, command.execute());
        verify(recoveryService).recoverExact(
                SESSION_ID, REMOTE_SESSION_ID, RETAINED_HEAD, ACCEPTED_COMMIT);
    }

    @Test
    void rejectsAnInvalidRemoteIdentityBeforeRecovery() {
        environment.setProperty(
                "atenea.operations.retained-draft-recovery.expected-remote-session-id",
                "not-a-uuid");

        assertEquals(1, command.execute());
        verify(recoveryService, never()).recoverExact(
                SESSION_ID, REMOTE_SESSION_ID, RETAINED_HEAD, ACCEPTED_COMMIT);
    }

    @Test
    void rejectsAResponseThatExposesValues() {
        when(recoveryService.recoverExact(SESSION_ID, REMOTE_SESSION_ID, RETAINED_HEAD, ACCEPTED_COMMIT))
                .thenReturn(new RecoverDraftWorkSessionResponse(
                SESSION_ID,
                7L,
                RETAINED_HEAD,
                ACCEPTED_COMMIT,
                "a".repeat(64),
                0,
                28,
                16,
                true));

        assertEquals(1, command.execute());
    }
}
