package com.atenea.android.coreconsole

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atenea.android.api.LegacyRemoteClosePlan
import com.atenea.android.api.MobileSessionOperatorState

class RemoteCloseVisualFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val confirmation = intent.getBooleanExtra("confirmation", false)
        val stale = intent.getBooleanExtra("stale", false)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        RemoteCloseOperatorPanel(
                            serverState = syntheticState,
                            actionState = RemoteCloseActionUiState(
                                plan = syntheticPlan.takeIf { confirmation },
                                requiresRefresh = stale,
                                error = "El estado cambió o la confirmación caducó. Actualiza y genera una nueva confirmación."
                                    .takeIf { stale }
                            ),
                            currentWorkSessionId = 17,
                            operatorRole = "PLATFORM_ADMINISTRATOR",
                            onPrimaryAction = {},
                            onConfirm = {},
                            onCancel = {}
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val syntheticState = MobileSessionOperatorState(
            surfaceEnabled = true,
            state = "CLOSED_OWNER_BLOCKS_CAPACITY",
            title = "Bloqueada por una sesión cerrada",
            blocker = "Otra sesión cerrada conserva la capacidad necesaria. El reintento estará disponible después de reconciliar su cierre.",
            primaryAction = "RECONCILE_REMOTE_CLOSE",
            primaryActionLabel = "Reconciliar cierre",
            primaryActionAvailable = true,
            requiredRole = "PLATFORM_ADMINISTRATOR",
            targetWorkSessionId = 16,
            targetAgentRunId = 96
        )

        val syntheticPlan = LegacyRemoteClosePlan(
            planId = "00000000-0000-0000-0000-000000000016",
            workSessionId = 16,
            operation = "RECONCILE_REMOTE_CLOSE",
            state = "READY_FOR_CONFIRMATION",
            requiredRole = "PLATFORM_ADMINISTRATOR",
            ownershipFingerprintSha256 = "a".repeat(64),
            expiresAt = "2026-08-04T18:00:00Z",
            consumed = false,
            expectedImpact = "Retirar sólo el ownership remoto activo.",
            valuesExposed = false,
            createdAt = "2026-08-04T17:55:00Z"
        )
    }
}
