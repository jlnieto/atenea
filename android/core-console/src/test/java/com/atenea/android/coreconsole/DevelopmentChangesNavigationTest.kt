package com.atenea.android.coreconsole

import com.atenea.android.api.MobileDevelopmentChange
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DevelopmentChangesNavigationTest {
    @Test
    fun `open change resumes its own session instead of the project overview session`() {
        val change = change(sessionId = 21)
        val unrelatedOverviewSessionId = 19L

        assertEquals(21L, change.sessionForReentry(expectedProjectId = 1))
        assertNotEquals(unrelatedOverviewSessionId, change.sessionForReentry(expectedProjectId = 1))
    }

    @Test
    fun `only an open change in the selected project can be resumed`() {
        assertNull(change(sessionId = 21).sessionForReentry(expectedProjectId = 2))
        assertNull(change(sessionId = 21, status = "PAUSED").sessionForReentry(expectedProjectId = 1))
        assertNull(change(sessionId = null).sessionForReentry(expectedProjectId = 1))
        assertNull(change(sessionId = 0).sessionForReentry(expectedProjectId = 1))
    }

    @Test
    fun `open changes appear before history without changing each groups recency`() {
        val completed = change(sessionId = null, status = "COMPLETED", title = "Anterior")
        val newestOpen = change(sessionId = 21, title = "Apache")
        val olderOpen = change(sessionId = 20, title = "Otro cambio")

        assertEquals(
            listOf(newestOpen, olderOpen, completed),
            orderedDevelopmentChanges(listOf(completed, newestOpen, olderOpen))
        )
    }

    private fun change(
        sessionId: Long?,
        status: String = "OPEN",
        title: String = "Monitorizar degradación de Apache cada 5 minutos"
    ) = MobileDevelopmentChange(
        changeKey = UUID.nameUUIDFromBytes(title.toByteArray()),
        projectId = 1,
        title = title,
        status = status,
        workspaceState = "READY",
        activeSessionId = sessionId,
        primaryActionLabel = null,
        updatedAt = null
    )
}
