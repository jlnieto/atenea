package com.atenea.android.coreconsole

import com.atenea.android.api.OperationsIncident
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthOverviewTest {
    @Test fun `unavailable ssh warning does not appear as critical health`() {
        val warning = incident("WARNING")

        assertEquals(OperationalLevel.WARNING, HealthOverview(emptyList(), listOf(warning)).snapshot.level)
        assertEquals(OperationalLevel.WARNING, HealthOverview(emptyList(), emptyList(), listOf("Lectura incompleta")).snapshot.level)
    }

    @Test fun `confirmed critical incident remains critical`() {
        assertEquals(
            OperationalLevel.CRITICAL,
            HealthOverview(emptyList(), listOf(incident("CRITICAL"))).snapshot.level
        )
    }

    private fun incident(severity: String) = OperationsIncident(
        id = 1L,
        hostId = 3L,
        hostName = "dedicado",
        serviceId = 9L,
        serviceName = "apache",
        status = "OPEN",
        severity = severity,
        title = "Comprobación SSH no disponible",
        summary = "El estado de Apache es desconocido.",
        openedAt = null,
        lastActivityAt = null,
        resolvedAt = null
    )
}
