package com.atenea.android.coreconsole

import com.atenea.android.api.CodexProgressEvent
import com.atenea.android.api.CodexProgressReplay
import com.atenea.android.api.CodexRunDetail
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexRunReaderTest {
    @Test fun `conversation and recovery share detail and incremental progress`() = runBlocking {
        val owner = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var details = 0
            val cursors = mutableListOf<Long>()
            val release = CompletableDeferred<Unit>()
            val reader = CodexRunReader(29, owner, { id -> details++; release.await(); detail(id) }, { _, after ->
                cursors += after; replay(after, after + 1)
            })
            val conversation = async(start = CoroutineStart.UNDISPATCHED) { reader.observe(103) }
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { reader.observe(103) }
            assertEquals(1, details); assertFalse(recovery.isCompleted)
            release.complete(Unit)
            assertEquals(conversation.await(), recovery.await())
            assertEquals(listOf(0L), cursors)
            val fresh = reader.observe(103)
            assertEquals(listOf(0L, 1L), cursors)
            assertEquals(listOf(1L, 2L), fresh.progress.events.map { it.sequence })
            assertEquals(2, details)
        } finally { owner.cancel() }
    }

    @Test fun `terminal evidence is reused only after final progress was received`() = runBlocking {
        var details = 0
        var progress = 0
        val reader = CodexRunReader(29, this, { id -> details++; detail(id, "SUCCEEDED") }, { _, after ->
            progress++; replay(after, progress.toLong(), terminal = progress > 1)
        })
        assertFalse(reader.observe(103).complete)
        val terminal = reader.observe(103)
        assertTrue(terminal.complete)
        repeat(5) { assertEquals(terminal, reader.observe(103)) }
        assertEquals(2, details); assertEquals(2, progress)
    }

    @Test fun `new execution starts at zero and does not replace the previous final result`() = runBlocking {
        val cursors = mutableListOf<Pair<Long, Long>>()
        val reader = CodexRunReader(29, this, { id -> detail(id, "FAILED") }, { id, after ->
            cursors += id to after; replay(after, 1, terminal = true)
        })
        val first = reader.observe(103)
        assertEquals(104L, reader.observe(104).detail.runId)
        assertEquals(first, reader.observe(103))
        assertEquals(listOf(103L to 0L, 104L to 0L), cursors)
    }

    @Test fun `foreign session or execution is rejected before progress and never retained`() = runBlocking {
        val owner = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var progress = 0
            var foreign = true
            val reader = CodexRunReader(29, owner, { id -> detail(if (foreign) id + 1 else id).copy(workSessionId = 30) }, { _, after ->
                progress++; replay(after, 1)
            })
            assertFailsWith<IllegalArgumentException> { reader.observe(103) }
            foreign = false
            assertFailsWith<IllegalArgumentException> { reader.observe(103) }
            assertEquals(0, progress)
        } finally { owner.cancel() }
    }

    @Test fun `separate authenticated owners do not share cached results`() = runBlocking {
        var calls = 0
        suspend fun load(id: Long) = detail(id, "SUCCEEDED").also { calls++ }
        val first = CodexRunReader(29, this, ::load) { _, after -> replay(after, 1, terminal = true) }
        val second = CodexRunReader(29, this, ::load) { _, after -> replay(after, 1, terminal = true) }
        first.observe(103); second.observe(103)
        assertEquals(2, calls)
    }

    @Test fun `a progress error preserves cursor and does not cancel the UI owner`() = runBlocking {
        val owner = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val cursors = mutableListOf<Long>()
            val reader = CodexRunReader(29, owner, { id -> detail(id) }, { _, after ->
                cursors += after
                if (cursors.size == 2) throw IOException("synthetic disconnection")
                replay(after, after + 1)
            })
            reader.observe(103)
            assertFailsWith<IOException> { reader.observe(103) }
            assertEquals(listOf(1L, 2L), reader.observe(103).progress.events.map { it.sequence })
            assertEquals(listOf(0L, 1L, 1L), cursors)
            assertTrue(owner.coroutineContext[Job]!!.isActive)
        } finally { owner.cancel() }
    }

    @Test fun `retained display history is bounded and evicted results are read fresh`() = runBlocking {
        var details = 0
        val reader = CodexRunReader(29, this, { id -> details++; detail(id, "SUCCEEDED") }, { _, after ->
            replay(after, 1, terminal = true)
        })
        (100L..108L).forEach { reader.observe(it) }
        reader.observe(100)
        assertEquals(10, details)
    }

    private fun detail(id: Long, status: String = "RUNNING") = CodexRunDetail(id, 29, status, null, null, null, null,
        null, null, status, 1, 1, 1_000, null, null)

    private fun replay(after: Long, sequence: Long, terminal: Boolean = false): CodexProgressReplay {
        val event = CodexProgressEvent(sequence, "CHECKING", "Progreso sintético", null)
        return CodexProgressReplay(after, 1, false, "CHECKING", event,
            if (terminal) "SUCCEEDED" else null, 1_000, "WAIT", listOf(event))
    }
}
