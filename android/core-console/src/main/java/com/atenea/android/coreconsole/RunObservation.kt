package com.atenea.android.coreconsole

/** AgentRun IDs are server-issued, monotonic identities; an older reply cannot undo a newly admitted run. */
internal fun acceptsRunObservation(observedRunId: Long?, retainedRunId: Long?): Boolean =
    retainedRunId == null || (observedRunId != null && observedRunId >= retainedRunId)
