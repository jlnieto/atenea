package com.atenea.android.coreconsole

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler

// Fixed operator route, never supplied by a prompt, server error or AgentRun.
internal const val ATENEA_PRIVATE_RESCUE = "https://atenea-control.tailf11cbc.ts.net:8443"

@Composable
internal fun IndependentRescueEntry(onOpen: (() -> Unit)? = null) {
    val uri = LocalUriHandler.current
    var explain by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    AteneaOutlinedButton(text = "Rescate independiente", onClick = { explain = true })
    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text("Si Atenea no responde") },
        text = { Text("Conecta Tailscale en este móvil y abre el acceso privado. " +
            "Puedes consultar el estado y preparar un reinicio de App sin cambiar versión ni datos. " +
            "Abrirlo no ejecuta ninguna recuperación. Guárdalo como favorito del navegador; también funciona si Atenea no abre." +
            if (openFailed) " No se pudo abrir el navegador. Abre $ATENEA_PRIVATE_RESCUE manualmente." else "") },
        confirmButton = { AteneaButton(text = "Abrir acceso privado", onClick = {
            try { if (onOpen != null) onOpen() else uri.openUri(ATENEA_PRIVATE_RESCUE); explain = false }
            catch (_: IllegalArgumentException) { openFailed = true }
        }) },
        dismissButton = { AteneaOutlinedButton(text = "Volver", onClick = { explain = false }) }
    )
}
