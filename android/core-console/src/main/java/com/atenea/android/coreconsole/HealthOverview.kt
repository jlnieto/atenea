package com.atenea.android.coreconsole

import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.OperationsHostStatus
import com.atenea.android.api.OperationsIncident

internal data class HealthOverview(
    val hosts: List<OperationsHostStatus>,
    val incidents: List<OperationsIncident>,
    val errors: List<String> = emptyList()
) {
    val snapshot: ShellHealthSnapshot
        get() {
            if (errors.isNotEmpty()) {
                return ShellHealthSnapshot(
                    level = OperationalLevel.CRITICAL,
                    totalHosts = hosts.size,
                    issueCount = errors.size + hosts.sumOf { it.unhealthyWebsites + it.openIncidents.size }
                )
            }
            if (hosts.isEmpty()) {
                return ShellHealthSnapshot(OperationalLevel.UNKNOWN, totalHosts = 0, issueCount = 0)
            }
            val issueCount = hosts.sumOf { it.unhealthyWebsites + it.openIncidents.size } + incidents.size
            val warningCount = hosts.count {
                it.websiteChecks.isEmpty() || it.hostStatusRun?.status == "FAILED"
            }
            val level = when {
                issueCount > 0 -> OperationalLevel.CRITICAL
                warningCount > 0 -> OperationalLevel.WARNING
                else -> OperationalLevel.OK
            }
            return ShellHealthSnapshot(level = level, totalHosts = hosts.size, issueCount = issueCount)
        }
}

internal data class ShellHealthSnapshot(
    val level: OperationalLevel,
    val totalHosts: Int,
    val issueCount: Int
) {
    val label: String
        get() = when (level) {
            OperationalLevel.OK -> "OK"
            OperationalLevel.WARNING -> "REV"
            OperationalLevel.CRITICAL -> issueCount.coerceAtLeast(1).toString()
            OperationalLevel.UNKNOWN -> "-"
        }

    companion object {
        val UNKNOWN = ShellHealthSnapshot(OperationalLevel.UNKNOWN, totalHosts = 0, issueCount = 0)
    }
}

internal suspend fun AteneaApiClient.fetchHealthOverview(): HealthOverview {
    val errors = mutableListOf<String>()
    val hosts = try {
        fetchOperationsHosts()
    } catch (exception: Exception) {
        errors += exception.message ?: "No se pudieron cargar los servidores."
        emptyList()
    }
    val statuses = hosts.mapNotNull { host ->
        try {
            fetchOperationsHostStatus(host.id)
        } catch (exception: Exception) {
            errors += "${host.name}: ${exception.message ?: "sin estado"}"
            null
        }
    }
    val incidents = try {
        fetchOperationsIncidents()
    } catch (exception: Exception) {
        errors += exception.message ?: "No se pudieron cargar las incidencias."
        emptyList()
    }
    return HealthOverview(hosts = statuses, incidents = incidents, errors = errors)
}
