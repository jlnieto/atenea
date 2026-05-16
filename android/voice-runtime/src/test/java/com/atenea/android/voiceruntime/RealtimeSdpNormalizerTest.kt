package com.atenea.android.voiceruntime

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RealtimeSdpNormalizerTest {
    @Test
    fun extractsPlainSdpAnswer() {
        val sdp = "v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\n"

        assertEquals(sdp.trim(), RealtimeSdpNormalizer.normalizeAnswerBody("  $sdp  "))
    }

    @Test
    fun extractsJsonSdpAnswer() {
        val result = RealtimeSdpNormalizer.normalizeAnswerBody("""{"sdp":"v=0\nm=audio 9 UDP/TLS/RTP/SAVPF 111\n"}""")

        assertTrue(result.startsWith("v=0"))
    }

    @Test
    fun rejectsInvalidAnswer() {
        assertFailsWith<IllegalStateException> {
            RealtimeSdpNormalizer.normalizeAnswerBody("""{"error":"bad"}""")
        }
    }

    @Test
    fun normalizesAndroidSensitiveRemoteSdpRules() {
        val normalized = RealtimeSdpNormalizer.normalizeRemoteAnswerForAndroid(
            """
            v=0
            a=msid-semantic:WMS *
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=msid:stream-123 track-456
            """.trimIndent()
        )

        assertContains(normalized.changes, "msid-semantic-space")
        assertContains(normalized.changes, "msid-semantic-stream-id")
        assertContains(normalized.changes, "crlf-trailing-newline")
        assertTrue(normalized.sdp.contains("a=msid-semantic: WMS stream-123"))
        assertTrue(normalized.sdp.endsWith("\r\n"))
    }
}
