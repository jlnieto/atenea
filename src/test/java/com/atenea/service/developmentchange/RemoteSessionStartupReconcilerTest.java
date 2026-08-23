package com.atenea.service.developmentchange;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.atenea.developmentchange.RemoteWorkBetaProperties;
import org.junit.jupiter.api.Test;

class RemoteSessionStartupReconcilerTest {

    @Test
    void bothNewCapabilitiesAreFalseByDefaultAndStartupHasZeroEffects() {
        RemoteWorkBetaProperties properties = new RemoteWorkBetaProperties();
        RemoteSessionService service = mock(RemoteSessionService.class);
        RemoteSessionStartupReconciler reconciler =
                new RemoteSessionStartupReconciler(properties, service);

        assertFalse(properties.isOpenOrResolveEnabled());
        assertFalse(properties.isRecoveryEnabled());
        reconciler.run(null);

        verify(service, never()).recoverIncompleteOperations();
    }
}
