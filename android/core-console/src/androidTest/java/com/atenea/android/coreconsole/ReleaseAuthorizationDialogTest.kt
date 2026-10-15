package com.atenea.android.coreconsole

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.atenea.android.api.MobileDeliveryOperation
import com.atenea.android.api.MobileDeliveryTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ReleaseAuthorizationDialogTest {
    @get:Rule val compose = createComposeRule()
    private var authorized = 0
    private var confirmed = 0
    private var configured = 0
    private var dismissed = 0

    private fun show(passkey: Boolean = true, requireSetup: Boolean = false) {
        val plan = MobileDeliveryOperation(UUID.randomUUID(), UUID.randomUUID(), 21, "RELEASE", MobileDeliveryTarget.APP_PROD,
            "1".repeat(40), "READY", "2".repeat(64), null, null, null, Long.MAX_VALUE, null, null, null, null)
        compose.setContent {
            AteneaOperatorTheme {
                ReleaseAuthorizationDialog(plan, { true }, passkey,
                    { authorized++; if (requireSetup) throw ReleasePasskeySetupRequired() else UUID.randomUUID() },
                    { authorized++; UUID.randomUUID() }, { configured++ }, { confirmed++ }, {}, {}, { dismissed++ })
            }
        }
    }

    @Test fun phoneConfirmationIsPrimaryAndCancelNeverPublishes() {
        show()
        compose.onNodeWithText("Confirmar con el móvil").assertIsDisplayed()
        compose.onNodeWithText("Código de Authenticator").assertDoesNotExist()
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(0, authorized); assertEquals(0, confirmed); assertEquals(1, dismissed)
    }

    @Test fun explicitAuthenticatorFallbackStillRequiresSixDigits() {
        show()
        compose.onNodeWithText("Usar Authenticator").performScrollTo().performClick()
        compose.onNodeWithText("Confirmar con Authenticator").assertIsNotEnabled()
        compose.onNodeWithText("Código de Authenticator").performTextInput("123456")
        compose.onNodeWithText("Confirmar con Authenticator").performClick()
        compose.waitForIdle()
        assertEquals(1, authorized); assertEquals(1, confirmed)
    }

    @Test fun setupNeverPublishesAndAuthenticatorRemainsAvailableWhenSetupIsRequired() {
        show(requireSetup = true)
        compose.onNodeWithText("Confirmar con el móvil").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Usar Authenticator").assertIsDisplayed()
        compose.onNodeWithText("Configurar confirmación del móvil").performClick()
        compose.onNodeWithText("Código de Authenticator").performTextInput("123456")
        compose.onNodeWithText("Activar confirmación del móvil").performClick()
        compose.waitForIdle()
        assertEquals(1, configured); assertEquals(0, confirmed)
        compose.onNodeWithText("Confirmar con el móvil").assertIsDisplayed()
    }
}
