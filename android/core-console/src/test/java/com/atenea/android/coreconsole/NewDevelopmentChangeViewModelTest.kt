package com.atenea.android.coreconsole

import com.atenea.android.api.NewDevelopmentChangeRequestKeys
import com.atenea.android.api.NewDevelopmentChangeResult
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NewDevelopmentChangeViewModelTest {

    @Test
    fun `successful flow returns direct conversation navigation and clears dialog state`() = runBlocking {
        val keys = requestKeys()
        val viewModel = NewDevelopmentChangeViewModel(
            startChange = { projectId, title, receivedKeys ->
                assertEquals(7L, projectId)
                assertEquals("Cambio movil", title)
                assertSame(keys, receivedKeys)
                NewDevelopmentChangeResult(CHANGE_KEY, sessionId = 91)
            },
            keysFactory = { keys }
        )
        viewModel.open(7)
        viewModel.updateTitle("  Cambio movil  ")

        val navigation = viewModel.submit()

        assertEquals(NewDevelopmentChangeNavigation(7, 91), navigation)
        assertFalse(viewModel.state.value.visible)
        assertFalse(viewModel.state.value.submitting)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `failed attempt stays unready and retry reuses exact idempotency keys`() = runBlocking {
        val keys = requestKeys()
        val receivedKeys = mutableListOf<NewDevelopmentChangeRequestKeys>()
        var calls = 0
        val viewModel = NewDevelopmentChangeViewModel(
            startChange = { _, _, currentKeys ->
                receivedKeys += currentKeys
                calls++
                if (calls == 1) error("El cambio se creo, pero el workspace no esta listo.")
                NewDevelopmentChangeResult(CHANGE_KEY, sessionId = 92)
            },
            keysFactory = { keys }
        )
        viewModel.open(7)
        viewModel.updateTitle("Cambio recuperable")

        val failedNavigation = viewModel.submit()

        assertNull(failedNavigation)
        assertTrue(viewModel.state.value.visible)
        assertFalse(viewModel.state.value.submitting)
        assertTrue(viewModel.state.value.attemptStarted)
        assertTrue(viewModel.state.value.error.orEmpty().contains("workspace"))
        viewModel.updateTitle("No debe sustituir el intento")
        assertEquals("Cambio recuperable", viewModel.state.value.title)

        val recoveredNavigation = viewModel.submit()

        assertEquals(NewDevelopmentChangeNavigation(7, 92), recoveredNavigation)
        assertEquals(2, receivedKeys.size)
        assertSame(receivedKeys[0], receivedKeys[1])
    }

    private fun requestKeys() = NewDevelopmentChangeRequestKeys(
        create = UUID.fromString("00000000-0000-0000-0000-000000000011"),
        provision = UUID.fromString("00000000-0000-0000-0000-000000000012"),
        openSession = UUID.fromString("00000000-0000-0000-0000-000000000013")
    )

    private companion object {
        val CHANGE_KEY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000041")
    }
}
