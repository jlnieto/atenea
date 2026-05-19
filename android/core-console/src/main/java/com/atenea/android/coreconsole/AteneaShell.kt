package com.atenea.android.coreconsole

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atenea.android.api.AteneaApiClient
import kotlinx.coroutines.launch

@Composable
internal fun AteneaShell(
    apiClient: AteneaApiClient,
    operatorName: String,
    updateManifestUrl: String,
    currentVersionCode: Int,
    currentVersionName: String,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = remember(updateManifestUrl, currentVersionCode) {
        AteneaUpdateManager(
            context = context.applicationContext,
            manifestUrl = updateManifestUrl,
            currentVersionCode = currentVersionCode
        )
    }
    var selectedDestination by rememberSaveable { mutableStateOf(AteneaDestination.HOME) }
    var selectedProjectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var updateState by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateProgress by remember { mutableStateOf<UpdateDownloadProgress?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var healthSnapshot by remember { mutableStateOf(ShellHealthSnapshot.UNKNOWN) }
    var healthLoading by remember { mutableStateOf(false) }
    val immersiveWorkSurface = selectedDestination == AteneaDestination.CONVERSATION ||
        selectedDestination == AteneaDestination.RESCUE

    fun refreshHeaderHealth() {
        scope.launch {
            healthLoading = true
            try {
                healthSnapshot = apiClient.fetchHealthOverview().snapshot
            } catch (_: Exception) {
                healthSnapshot = ShellHealthSnapshot(OperationalLevel.CRITICAL, totalHosts = 0, issueCount = 1)
            } finally {
                healthLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (updateManifestUrl.isNotBlank()) {
            try {
                updateState = updateManager.check()
            } catch (_: Exception) {
                updateState = null
            }
        }
        refreshHeaderHealth()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(304.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("Atenea", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(operatorName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    AteneaDrawerSection("Trabajo")
                    listOf(AteneaDestination.HOME, AteneaDestination.PROJECTS, AteneaDestination.CORE, AteneaDestination.VOICE).forEach { destination ->
                        DrawerDestination(
                            destination = destination,
                            selectedDestination = selectedDestination,
                            onSelect = {
                                selectedDestination = it
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                    AteneaDrawerSection("Operacion")
                    listOf(AteneaDestination.HEALTH, AteneaDestination.OPERATIONS, AteneaDestination.FILES)
                        .forEach { destination ->
                            DrawerDestination(
                                destination = destination,
                                selectedDestination = selectedDestination,
                                onSelect = {
                                    selectedDestination = it
                                    scope.launch { drawerState.close() }
                                }
                            )
                        }
                    AteneaDrawerSection("Sistema")
                    listOf(AteneaDestination.COSTS, AteneaDestination.DIAGNOSTICS, AteneaDestination.SETTINGS).forEach { destination ->
                        DrawerDestination(
                            destination = destination,
                            selectedDestination = selectedDestination,
                            onSelect = {
                                selectedDestination = it
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (updateState is UpdateCheckResult.Available) {
                        Text("Actualización disponible", style = MaterialTheme.typography.bodySmall)
                    }
                    AteneaDrawerRow(label = "Salir", selected = false, onClick = onLogout)
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (!immersiveWorkSurface) {
                    AteneaTopChrome(
                        title = selectedDestination.title,
                        healthSnapshot = healthSnapshot,
                        healthLoading = healthLoading,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onHealthClick = { selectedDestination = AteneaDestination.HEALTH }
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .then(
                        if (immersiveWorkSurface) {
                            Modifier
                        } else {
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        }
                    )
            ) {
                when (selectedDestination) {
                    AteneaDestination.HOME -> HomeScreen(
                        apiClient = apiClient
                    )
                    AteneaDestination.HEALTH -> HealthScreen(
                        apiClient = apiClient,
                        onSnapshotChanged = { healthSnapshot = it }
                    )
                    AteneaDestination.CORE -> CoreScreen(apiClient = apiClient)
                    AteneaDestination.VOICE -> VoiceScreen(apiClient = apiClient)
                    AteneaDestination.PROJECTS -> ProjectsScreen(
                        apiClient = apiClient,
                        onOpenSession = { projectId, sessionId ->
                            selectedProjectId = projectId
                            selectedSessionId = sessionId
                            selectedDestination = AteneaDestination.SESSION
                        },
                        onOpenRescue = { projectId ->
                            selectedProjectId = projectId
                            selectedDestination = AteneaDestination.RESCUE
                        }
                    )
                    AteneaDestination.SESSION -> WorkSessionScreen(
                        apiClient = apiClient,
                        projectId = selectedProjectId,
                        sessionId = selectedSessionId,
                        onOpenConversation = { selectedDestination = AteneaDestination.CONVERSATION },
                        onOpenCore = { selectedDestination = AteneaDestination.CORE },
                        onBackToProjects = { selectedDestination = AteneaDestination.PROJECTS }
                    )
                    AteneaDestination.CONVERSATION -> WorkSessionConversationScreen(
                        apiClient = apiClient,
                        projectId = selectedProjectId,
                        sessionId = selectedSessionId,
                        onOpenCore = { selectedDestination = AteneaDestination.CORE },
                        onBackToSession = { selectedDestination = AteneaDestination.SESSION }
                    )
                    AteneaDestination.RESCUE -> RescueScreen(
                        apiClient = apiClient,
                        projectId = selectedProjectId,
                        onOpenCore = { selectedDestination = AteneaDestination.CORE },
                        onBackToProjects = { selectedDestination = AteneaDestination.PROJECTS }
                    )
                    AteneaDestination.OPERATIONS -> OperationsScreen(
                        apiClient = apiClient,
                        onHealthSnapshotChanged = { healthSnapshot = it }
                    )
                    AteneaDestination.FILES -> FilesScreen(apiClient = apiClient)
                    AteneaDestination.COSTS -> CostsScreen(apiClient = apiClient)
                    AteneaDestination.DIAGNOSTICS -> DiagnosticsScreen(apiClient = apiClient)
                    AteneaDestination.SETTINGS -> SettingsScreen(
                        apiClient = apiClient,
                        updateState = updateState,
                        updateMessage = updateMessage,
                        updateProgress = updateProgress,
                        checkingUpdate = checkingUpdate,
                        currentVersionCode = currentVersionCode,
                        currentVersionName = currentVersionName,
                        onCheckUpdate = {
                            scope.launch {
                                checkingUpdate = true
                                updateMessage = null
                                updateProgress = null
                                try {
                                    updateState = updateManager.check()
                                } catch (updateError: Exception) {
                                    updateMessage = updateError.message ?: "No se pudo comprobar la actualización."
                                } finally {
                                    checkingUpdate = false
                                }
                            }
                        },
                        onInstallUpdate = { update ->
                            scope.launch {
                                checkingUpdate = true
                                updateMessage = "Preparando descarga..."
                                updateProgress = null
                                try {
                                    updateMessage = when (updateManager.downloadAndOpenInstaller(update) { progress ->
                                        updateProgress = progress
                                        updateMessage = "Descargando ${progress.downloadedBytes.formatBytes()} de ${progress.totalBytes?.formatBytes() ?: "tamaño desconocido"}"
                                    }) {
                                        UpdateInstallResult.InstallerOpened ->
                                            "Instalador abierto. Confirma la actualización en Android."
                                        UpdateInstallResult.NeedsInstallPermission ->
                                            "Permite instalar apps desde Atenea y vuelve a pulsar actualizar."
                                    }
                                } catch (updateError: Exception) {
                                    updateMessage = updateError.message ?: "No se pudo descargar la actualización."
                                } finally {
                                    checkingUpdate = false
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerDestination(
    destination: AteneaDestination,
    selectedDestination: AteneaDestination,
    onSelect: (AteneaDestination) -> Unit
) {
    AteneaDrawerRow(
        label = destination.label,
        selected = selectedDestination == destination,
        onClick = { onSelect(destination) }
    )
}

private enum class AteneaDestination(
    val label: String,
    val title: String
) {
    HOME("Inicio", "Inicio"),
    PROJECTS("Proyectos", "Proyectos"),
    SESSION("Sesión", "Sesión"),
    CONVERSATION("Conversación", "Conversación"),
    RESCUE("Rescate", "Rescate"),
    HEALTH("Estado", "Estado"),
    CORE("Core", "Core"),
    VOICE("Voz", "Voz"),
    OPERATIONS("Operaciones", "Operaciones"),
    FILES("Archivos", "Archivos"),
    COSTS("Costes API", "Costes API"),
    DIAGNOSTICS("Diagnostico", "Diagnostico"),
    SETTINGS("Ajustes", "Ajustes")
}
