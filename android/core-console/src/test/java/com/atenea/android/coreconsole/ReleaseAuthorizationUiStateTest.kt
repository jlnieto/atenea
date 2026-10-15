package com.atenea.android.coreconsole

import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.MobileDeliveryTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID
import kotlin.test.*

class ReleaseAuthorizationUiStateTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val grant = UUID.randomUUID()

    @Test fun verifiedPasskeyConfirmsOnlyOnceAndRefreshesBeforeClosing() {
        val events = mutableListOf<String>()
        val state = ReleaseAuthorizationUiState(scope, { true }, { assertNull(it); events += "authorize"; grant },
            { assertEquals(grant, it); events += "confirm" }, { events += "refresh" }, { events += "accepted" })
        state.submit(); state.submit()
        assertEquals(listOf("authorize", "confirm", "refresh", "accepted"), events)
        assertFalse(state.busy)
    }

    @Test fun authenticatorRemainsAnExplicitAlternativeForTheSameConfirmation() {
        var confirmed = 0
        val state = ReleaseAuthorizationUiState(scope, { true }, { assertEquals("123456", it); grant },
            { confirmed++ }, {}, {})
        state.submit("123456")
        assertEquals(1, confirmed)
    }

    @Test fun doubleTapWhilePhoneUnlockIsPendingDoesNotRequestTwoChallenges() {
        val unlock = CompletableDeferred<UUID>()
        var authorizations = 0; var confirmations = 0
        val state = ReleaseAuthorizationUiState(scope, { true }, { authorizations++; unlock.await() },
            { confirmations++ }, {}, {})
        state.submit(); state.submit()
        assertTrue(state.busy); assertEquals(1, authorizations); assertEquals(0, confirmations)
        unlock.complete(grant)
        assertEquals(1, confirmations)
    }

    @Test fun cancellingPhoneUnlockDoesNotPublishOrFallBackAutomatically() {
        var confirmed = 0
        val state = ReleaseAuthorizationUiState(scope, { true }, { throw ReleaseAuthorizationCancelled() },
            { confirmed++ }, {}, {})
        state.submit()
        assertEquals(0, confirmed); assertFalse(state.confirmationAttempted); assertFalse(state.busy)
        assertTrue(state.message!!.contains("No se ha solicitado"))
    }

    @Test fun unavailablePasskeyDoesNotPublishAndAllowsExplicitAuthenticatorChoice() {
        var confirmed = 0
        val state = ReleaseAuthorizationUiState(scope, { true }, { if (it == null) throw ReleasePasskeyUnavailable() else grant },
            { confirmed++ }, {}, {})
        state.submit()
        assertEquals(0, confirmed); assertTrue(state.message!!.contains("Authenticator"))
        state.submit("123456")
        assertEquals(1, confirmed)
    }

    @Test fun initialTrustNeedsOneSetupButNeverBlocksAuthenticatorOrPublishesImplicitly() {
        var confirmations = 0
        val state = ReleaseAuthorizationUiState(scope, { true }, { if (it == null) throw ReleasePasskeySetupRequired() else grant },
            { confirmations++ }, {}, {})
        state.submit()
        assertTrue(state.needsSetup); assertEquals(0, confirmations)
        state.setupCompleted()
        assertFalse(state.needsSetup); assertEquals(0, confirmations)
        state.submit("123456")
        assertEquals(1, confirmations)
    }

    @Test fun rejectedFactorDoesNotPublishOrRevealProviderDetails() {
        var confirmed = 0
        val state = ReleaseAuthorizationUiState(scope, { true }, { error("credential-private-detail") },
            { confirmed++ }, {}, {})
        state.submit()
        assertEquals(0, confirmed); assertFalse(state.message!!.contains("credential-private-detail"))
    }

    @Test fun planChangingDuringUnlockStopsBeforeConfirm() {
        var current = true; var confirmed = 0
        val unlock = CompletableDeferred<UUID>()
        val state = ReleaseAuthorizationUiState(scope, { current }, { unlock.await() }, { confirmed++ }, {}, {})
        state.submit(); current = false; unlock.complete(grant)
        assertEquals(0, confirmed); assertFalse(state.confirmationAttempted)
    }

    @Test fun lostConfirmationReplyMustBeObservedWithoutSendingAnotherRequestOrFactor() {
        var authorized = 0; var confirmed = 0; var refreshed = 0
        val state = ReleaseAuthorizationUiState(scope, { true }, { authorized++; grant },
            { confirmed++; error("response lost") }, { refreshed++ }, {})
        state.submit(); state.submit(); state.submit("123456")
        assertEquals(1, authorized); assertEquals(1, confirmed); assertEquals(1, refreshed)
        assertTrue(state.confirmationAttempted); assertTrue(state.message!!.contains("misma publicación"))
    }

    @Test fun displayedApprovalMustStillMatchReadyPlanHashSourceTargetOwnerAndExpiry() {
        val plan = MobileDeliveryOperation(UUID.randomUUID(), UUID.randomUUID(), 21, "RELEASE",
            MobileDeliveryTarget.APP_PROD, "1".repeat(40), "READY", "2".repeat(64), null,
            null, null, 100, null, null, null, null, null)
        assertTrue(sameConfirmableRelease(plan, plan, 99))
        assertFalse(sameConfirmableRelease(plan, plan, 100))
        for (changed in listOf(plan.copy(id=UUID.randomUUID()), plan.copy(sessionId=22), plan.copy(state="APPLYING"),
            plan.copy(sourceCommit="3".repeat(40)), plan.copy(planSha256="4".repeat(64)),
            plan.copy(target=MobileDeliveryTarget.ANDROID_STABLE), plan.copy(expiresAt=101))) {
            assertFalse(sameConfirmableRelease(plan, changed, 99))
        }
    }
}
