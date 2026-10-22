package com.atenea.android.coreconsole

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shares simultaneous observations, not effects or cached authority. Sequential reads are fresh. */
internal class CoalescingRead<K, V>(private val owner: CoroutineScope) {
    private val entries = Mutex()
    private val transport = Mutex()
    private val pending = mutableMapOf<K, Deferred<Result<V>>>()

    suspend fun read(key: K, load: suspend () -> V): V {
        val request = entries.withLock {
            pending[key]?.takeUnless { it.isCompleted } ?: owner.async(start = CoroutineStart.LAZY) {
                try {
                    Result.success(transport.withLock { load() })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // A failed observation belongs to its callers, not the UI owner's whole Job.
                    Result.failure(failure)
                } finally {
                    withContext(NonCancellable) { entries.withLock { pending.remove(key) } }
                }
            }.also { pending[key] = it; it.start() }
        }
        // Cancelling one waiter does not cancel another waiter's shared observation.
        return request.await().getOrThrow()
    }
}
