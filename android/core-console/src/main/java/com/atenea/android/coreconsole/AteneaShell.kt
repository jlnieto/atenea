package com.atenea.android.coreconsole

import android.content.Context
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
    val navigationStore = remember(context) {
        AteneaNavigationStore(context.applicationContext)
    }
    val restoredRoute = remember(navigationStore) {
        navigationStore.loadRoute()
    }
    val updateManager = remember(updateManifestUrl, currentVersionCode) {
        AteneaUpdateManager(
            context = context.applicationContext,
            manifestUrl = updateManifestUrl,
            currentVersionCode = currentVersionCode
        )
    }
    var selectedDestination by rememberSaveable { mutableStateOf(restoredRoute.destination) }
    var selectedProjectId by rememberSaveable { mutableStateOf(restoredRoute.projectId) }
    var selectedSessionId by rememberSaveable { mutableStateOf(restoredRoute.sessionId) }
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
    LaunchedEffect(selectedDestination, selectedProjectId, selectedSessionId) {
        navigationStore.saveRoute(selectedDestination, selectedProjectId, selectedSessionId)
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
                    AteneaDrawerRow(
                        label = "Salir",
                        selected = false,
                        onClick = {
                            navigationStore.clear()
                            onLogout()
                        }
                    )
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

private data class AteneaRouteSnapshot(
    val destination: AteneaDestination,
    val projectId: Long?,
    val sessionId: Long?
)

private class AteneaNavigationStore(context: Context) {
    private val preferences = context.getSharedPreferences("atenea_navigation", Context.MODE_PRIVATE)

    fun loadRoute(): AteneaRouteSnapshot {
        val projectId = preferences.getNullableLong(KEY_PROJECT_ID)
        val sessionId = preferences.getNullableLong(KEY_SESSION_ID)
        val destination = preferences.getString(KEY_DESTINATION, null)
            ?.let { value -> AteneaDestination.entries.firstOrNull { it.name == value } }
            ?: AteneaDestination.HOME
        return AteneaRouteSnapshot(
            destination = destination.validFor(projectId, sessionId),
            projectId = projectId,
            sessionId = sessionId
        )
    }

    fun saveRoute(destination: AteneaDestination, projectId: Long?, sessionId: Long?) {
        preferences.edit()
            .putString(KEY_DESTINATION, destination.validFor(projectId, sessionId).name)
            .putNullableLong(KEY_PROJECT_ID, projectId)
            .putNullableLong(KEY_SESSION_ID, sessionId)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun AteneaDestination.validFor(projectId: Long?, sessionId: Long?): AteneaDestination = when (this) {
        AteneaDestination.SESSION,
        AteneaDestination.CONVERSATION -> if (projectId != null && sessionId != null) this else AteneaDestination.PROJECTS
        AteneaDestination.RESCUE -> if (projectId != null) this else AteneaDestination.PROJECTS
        else -> this
    }

    private fun android.content.SharedPreferences.getNullableLong(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun android.content.SharedPreferences.Editor.putNullableLong(
        key: String,
        value: Long?
    ): android.content.SharedPreferences.Editor =
        if (value == null) {
            remove(key)
        } else {
            putLong(key, value)
        }

    private companion object {
        const val KEY_DESTINATION = "destination"
        const val KEY_PROJECT_ID = "projectId"
        const val KEY_SESSION_ID = "sessionId"
    }
}
