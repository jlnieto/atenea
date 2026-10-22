package com.atenea.android.coreconsole

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoalescingReadTest {
    @Test fun `simultaneous readers share one transport and result`() = runBlocking {
        val owner = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val reads = CoalescingRead<String, Int>(owner)
            val release = CompletableDeferred<Int>()
            var calls = 0
            val first = async(start = CoroutineStart.UNDISPATCHED) { reads.read("session-29") { calls++; release.await() } }
            val second = async(start = CoroutineStart.UNDISPATCHED) { reads.read("session-29") { calls++; 99 } }
            assertEquals(1, calls)
            assertFalse(second.isCompleted)
            release.complete(42)
            assertEquals(42, first.await()); assertEquals(42, second.await())
        } finally { owner.cancel() }
    }

    @Test fun `sequential observations never reuse a completed result`() = runBlocking {
        val reads = CoalescingRead<Unit, Int>(this)
        var calls = 0
        assertEquals(1, reads.read(Unit) { ++calls })
        assertEquals(2, reads.read(Unit) { ++calls })
    }

    @Test fun `new generation waits for old transport instead of overlapping or sharing its value`() = runBlocking {
        val owner = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val reads = CoalescingRead<Int, Int>(owner)
            val release = CompletableDeferred<Int>()
            var calls = 0
            val old = async(start = CoroutineStart.UNDISPATCHED) { reads.read(0) { calls++; release.await() } }
            val fresh = async(start = CoroutineStart.UNDISPATCHED) { reads.read(1) { calls++; 2 } }
            assertEquals(1, calls); assertFalse(fresh.isCompleted)
            release.complete(1)
            assertEquals(1, old.await()); assertEquals(2, fresh.await()); assertEquals(2, calls)
        } finally { owner.cancel() }
    }

    @Test fun `cancelling one waiter does not cancel another waiter or the owner's read`() = runBlocking {
        val owner = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val reads = CoalescingRead<Unit, Int>(owner)
            val release = CompletableDeferred<Int>()
            var calls = 0
            val first = async(start = CoroutineStart.UNDISPATCHED) { reads.read(Unit) { calls++; release.await() } }
            val second = async(start = CoroutineStart.UNDISPATCHED) { reads.read(Unit) { calls++; 99 } }
            first.cancel(); first.join()
            assertFalse(release.isCancelled); assertFalse(second.isCompleted)
            release.complete(42)
            assertEquals(42, second.await()); assertEquals(1, calls)
        } finally { owner.cancel() }
    }

    @Test fun `failed read is not cached and does not cancel owner`() = runBlocking {
        val owner = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val reads = CoalescingRead<Unit, Int>(owner)
            assertFailsWith<IOException> { reads.read(Unit) { throw IOException("synthetic read failure") } }
            assertTrue(owner.coroutineContext[Job]!!.isActive)
            assertEquals(42, reads.read(Unit) { 42 })
        } finally { owner.cancel() }
    }

    @Test fun `cancelling owner cancels its outstanding observation`() = runBlocking<Unit> {
        val owner = CoroutineScope(Job() + Dispatchers.Unconfined)
        val cancelled = CompletableDeferred<Unit>()
        val reads = CoalescingRead<Unit, Int>(owner)
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            reads.read(Unit) { try { CompletableDeferred<Int>().await() } finally { cancelled.complete(Unit) } }
        }
        owner.cancel()
        cancelled.await()
        assertFailsWith<kotlinx.coroutines.CancellationException> { request.await() }
    }
}
