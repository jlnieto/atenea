package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateCommand;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceUpdateBaseGuardTest {
    private final SourceUpdateStore store=mock(SourceUpdateStore.class);
    private final GitHubClient github=mock(GitHubClient.class);
    private final SourceUpdateBaseGuard guard=new SourceUpdateBaseGuard(store,github);

    private SourceUpdateOperation prepared() {
        var operation=mock(SourceUpdateOperation.class);
        var command=mock(DevelopmentChangeSourceUpdateCommand.class);
        when(operation.id()).thenReturn(UUID.randomUUID());
        when(operation.state()).thenReturn("RESOLVER_COMPLETED");
        when(operation.command()).thenReturn(command);
        when(command.targetMainCommit()).thenReturn("1".repeat(40));
        return operation;
    }

    @Test void freshReadDetectsMovedMainWithoutChangingThePinnedIntent() {
        var operation=prepared();
        when(github.canonicalMain(any())).thenReturn("1".repeat(40),"2".repeat(40));
        assertEquals(SourceUpdateBaseGuard.Status.CURRENT,guard.observe(operation));
        assertEquals(SourceUpdateBaseGuard.Status.UPDATE_REQUIRED,guard.observe(operation));
        assertEquals("1".repeat(40),operation.command().targetMainCommit());
        verify(store,times(2)).hasRetainedFinalization(operation.id());verifyNoMoreInteractions(store);
    }
    @Test void missingOrPendingPreparationDoesNotRequireGithub() {
        assertEquals(SourceUpdateBaseGuard.Status.NOT_REQUIRED,guard.observe(null));
        var operation=mock(SourceUpdateOperation.class);
        for (String phase:java.util.List.of("QUEUED","ATTENTION","BLOCKED","RESOLVING","PUBLISHED")) {
            when(operation.state()).thenReturn(phase);
            assertEquals(SourceUpdateBaseGuard.Status.NOT_REQUIRED,guard.observe(operation));
        }
        verifyNoInteractions(github,store);
    }
    @Test void uncertainRetainedFinalizationNeverOffersAnotherBasePreparation() {
        var operation=prepared();when(store.hasRetainedFinalization(operation.id())).thenReturn(true);
        assertEquals(SourceUpdateBaseGuard.Status.NOT_REQUIRED,guard.observe(operation));
        verifyNoInteractions(github);
    }
    @Test void unavailableOrMalformedMainFailsClosedWithoutInventingATarget() {
        var operation=prepared();when(store.latest(26L)).thenReturn(Optional.of(operation));
        when(github.canonicalMain(any())).thenThrow(new GitHubIntegrationException("synthetic unavailable"));
        assertEquals(SourceUpdateBaseGuard.Status.UNAVAILABLE,guard.observe(operation));
        assertEquals("SOURCE_BASE_OBSERVATION_UNAVAILABLE",assertThrows(DeliveryRejectedException.class,()->guard.requireCurrent(26L)).code());
        doReturn(null,"not-a-commit").when(github).canonicalMain(any());
        assertEquals(SourceUpdateBaseGuard.Status.UNAVAILABLE,guard.observe(operation));
        assertEquals(SourceUpdateBaseGuard.Status.UNAVAILABLE,guard.observe(operation));
        verify(store,never()).create(any(),any(),any(),any(),any());
    }
    @Test void movedMainRejectsAdmissionButCurrentAndUnrelatedSessionsRemainUsable() {
        var operation=prepared();when(store.latest(26L)).thenReturn(Optional.of(operation));
        when(github.canonicalMain(any())).thenReturn("2".repeat(40),"1".repeat(40));
        assertEquals("SOURCE_FINALIZATION_MAIN_MOVED",assertThrows(DeliveryRejectedException.class,()->guard.requireCurrent(26L)).code());
        assertDoesNotThrow(()->guard.requireCurrent(26L));
        assertDoesNotThrow(()->guard.requireCurrent(99L));
    }
}
