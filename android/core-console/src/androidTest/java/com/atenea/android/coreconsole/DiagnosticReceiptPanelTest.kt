package com.atenea.android.coreconsole

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.atenea.android.api.MobileDiagnosticReceipt
import java.util.UUID
import org.junit.Rule
import org.junit.Test

class DiagnosticReceiptPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedReportIsIdentifiableAndCopyableWithoutOpeningConversation() {
        val id = UUID.fromString("0cc7815a-f703-46ee-938a-8ef4d00e68a2")
        compose.setContent {
            AteneaOperatorTheme {
                DiagnosticReceiptPanel(MobileDiagnosticReceipt(id,
                    "2026-10-07T10:00:01Z", "2026-10-07T10:00:00Z", "0.5.109", 142,
                    "SM-M135F", 1024, "4".repeat(64), "/api/mobile/diagnostics/$id/content"))
            }
        }
        compose.onNodeWithText("Último diagnóstico guardado").assertIsDisplayed()
        compose.onNodeWithText(id.toString()).assertIsDisplayed()
        compose.onNodeWithText("SHA-256: ${"4".repeat(64)}").assertIsDisplayed()
        compose.onNodeWithText("Copiar ID del diagnóstico").assertIsDisplayed()
        compose.onNodeWithText("Selecciona una WorkSession", substring = true).assertDoesNotExist()
    }
}
