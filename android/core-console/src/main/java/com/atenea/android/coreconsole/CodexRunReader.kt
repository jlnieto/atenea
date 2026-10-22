package com.atenea.android.coreconsole

import com.atenea.android.api.AteneaApiClient
import com.atenea.android.api.CodexProgressReplay
import com.atenea.android.api.CodexRunDetail
import kotlinx.coroutines.CoroutineScope

internal data class CodexRunObservation(val detail: CodexRunDetail, val progress: CodexProgressReplay) {
    val complete get() = detail.status in setOf("SUCCEEDED", "FAILED", "CANCELLED") && progress.terminalOutcome != null
}

/** One display-only reader per authenticated conversation/session, never a global authority cache. */
internal class CodexRunReader(
    private val sessionId: Long,
    owner: CoroutineScope,
    private val loadDetail: suspend (Long) -> CodexRunDetail,
    private val loadProgress: suspend (Long, Long) -> CodexProgressReplay
) {
    constructor(api: AteneaApiClient, sessionId: Long, owner: CoroutineScope) :
        this(sessionId, owner, api::fetchCodexRunDetail, api::fetchCodexRunProgress)

    private val reads = CoalescingRead<Long, CodexRunObservation>(owner)
    private val retained = linkedMapOf<Long, CodexRunObservation>()

    suspend fun observe(runId: Long): CodexRunObservation = reads.read(runId) {
        require(runId > 0 && sessionId > 0)
        // All access to retained/cursors is serialized by the read coordinator.
        val previous = retained[runId]
        if (previous?.complete == true) return@read previous
        val detail = loadDetail(runId)
        require(detail.runId == runId && detail.workSessionId == sessionId)
        val replay = loadProgress(runId, previous?.progress?.latestObservedSequence() ?: 0)
        val next = CodexRunObservation(detail, mergeCodexProgressReplay(previous?.progress, replay))
        retained.remove(runId)
        retained[runId] = next
        // Only a bounded display window; eviction requires a fresh read, not invented history.
        if (retained.size > 8) retained.remove(retained.keys.first())
        next
    }
}
