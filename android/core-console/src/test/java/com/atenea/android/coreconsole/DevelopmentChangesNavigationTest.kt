package com.atenea.android.coreconsole

import com.atenea.android.api.MobileDevelopmentChange
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DevelopmentChangesNavigationTest {
    @Test
    fun `completed and abandoned changes belong only to history`() {
        assertTrue(developmentChangeHistory(change(null, "COMPLETED")))
        assertTrue(developmentChangeHistory(change(null, "ABANDONED")))
        assertFalse(developmentChangeHistory(change(null, "OPEN")))
        assertFalse(developmentChangeHistory(change(null, "PAUSED")))
    }

    @Test
    fun `closing or retiring conversation is not advertised as continuing work`() {
        val closing = change(20).copy(activeSessionStatus = "CLOSING")
        assertEquals("Ver conversación · cierre pendiente", changeConversationLabel(closing))
        assertEquals("Ver conversación · retirada pendiente", changeConversationLabel(closing.copy(retirementState = "BLOCKED")))
        assertEquals(20L, closing.sessionForReentry(1))
    }

    @Test
    fun `abandoned session history opens only its archived session`() {
        val archived = change(null, "ABANDONED").copy(archivedSessionId = 20)
        assertEquals(20L, archived.sessionForReentry(1))
        assertNull(archived.sessionForReentry(2))
        assertNull(archived.copy(activeSessionId = 19).sessionForReentry(1))
        assertNull(archived.copy(archivedSessionId = 0).sessionForReentry(1))
        assertEquals("Ver conversación", changeConversationLabel(archived))
    }
    @Test
    fun `completed ticket opens its preserved conversation without resolving another session`() {
        val completed = change(sessionId = null, status = "COMPLETED").copy(completedSessionId = 21)
        assertEquals(21L, completed.sessionForReentry(1))
        assertNull(completed.sessionForReentry(2))
        assertNull(completed.copy(completedSessionId = 0).sessionForReentry(1))
        assertNull(completed.copy(activeSessionId = 19).sessionForReentry(1))
        assertNull(completed.copy(status = "PAUSED").sessionForReentry(1))
    }
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
