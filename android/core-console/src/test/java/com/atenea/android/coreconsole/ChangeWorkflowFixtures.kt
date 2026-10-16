package com.atenea.android.coreconsole

import com.atenea.android.api.DevelopmentChangeValidationAttempt
import com.atenea.android.api.DevelopmentChangeValidationEvidence
import java.util.UUID

internal fun workflowEvidence(sessionId: Long, revision: Long = 1, state: String = "CURRENT", passed: Int = if (state == "CURRENT") 4 else 0): DevelopmentChangeValidationEvidence {
    val fingerprint = revision.toString(16).padStart(64, '0')
    val operations = listOf("BACKEND_TEST", "WEB_BUILD", "ANDROID_BUILD", "PLAYWRIGHT_ACCEPTANCE").take(passed).map { kind ->
        DevelopmentChangeValidationAttempt(UUID.nameUUIDFromBytes("$sessionId:$revision:$kind".toByteArray()), sessionId, kind,
            "SUCCEEDED", fingerprint, "synthetic-v1", 0, "PASS", null, null)
    }
    return DevelopmentChangeValidationEvidence(UUID.fromString("00000000-0000-4000-8000-000000000001"), sessionId,
        revision, fingerprint, state, passed, 4, operations, operations.lastOrNull())
}
