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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atenea.android.api.MobileSessionOperatorState

class RemoteCloseVisualFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stale = intent.getBooleanExtra("stale", false)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        RemoteCloseOperatorPanel(
                            serverState = syntheticState,
                            actionState = RemoteCloseActionUiState(
                                requiresRefresh = stale,
                                error = "El estado cambió. Actualiza antes de continuar."
                                    .takeIf { stale }
                            ),
                            operatorRole = "PLATFORM_ADMINISTRATOR",
                            onPrimaryAction = {}
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val syntheticState = MobileSessionOperatorState(
            surfaceEnabled = true,
            state = "CLOSING_REMOTE",
            title = "Cerrando el cambio",
            blocker = "Confirmación de la liberación pendiente.",
            primaryAction = "RECONCILE_REMOTE_CLOSE",
            primaryActionLabel = "Reconciliar cierre",
            primaryActionAvailable = true,
            requiredRole = "PLATFORM_ADMINISTRATOR",
            targetWorkSessionId = 21,
            targetAgentRunId = 96
        )

    }
}
