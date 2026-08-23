package com.atenea.auth.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpAlgorithmTest {
    private static final byte[] RFC_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void implementsRfc6238SixDigitSha1Vectors() {
        assertEquals("287082", TotpAlgorithm.code(RFC_SECRET, 59L / 30L));
        assertEquals("081804", TotpAlgorithm.code(RFC_SECRET, 1_111_111_109L / 30L));
        assertEquals("005924", TotpAlgorithm.code(RFC_SECRET, 1_234_567_890L / 30L));
    }

    @Test
    void acceptsOnlyPreviousCurrentAndNextWindows() {
        Instant now = Instant.ofEpochSecond(3_000_000L);
        long current = now.getEpochSecond() / TotpAlgorithm.STEP_SECONDS;
        assertEquals(current - 1, TotpAlgorithm.matchingCounter(
                RFC_SECRET, TotpAlgorithm.code(RFC_SECRET, current - 1), now).orElseThrow());
        assertEquals(current, TotpAlgorithm.matchingCounter(
                RFC_SECRET, TotpAlgorithm.code(RFC_SECRET, current), now).orElseThrow());
        assertEquals(current + 1, TotpAlgorithm.matchingCounter(
                RFC_SECRET, TotpAlgorithm.code(RFC_SECRET, current + 1), now).orElseThrow());
        assertFalse(TotpAlgorithm.matchingCounter(
                RFC_SECRET, TotpAlgorithm.code(RFC_SECRET, current - 2), now).isPresent());
        assertFalse(TotpAlgorithm.matchingCounter(
                RFC_SECRET, TotpAlgorithm.code(RFC_SECRET, current + 2), now).isPresent());
        assertTrue(TotpAlgorithm.matchingCounter(RFC_SECRET, "12345x", now).isEmpty());
    }
}
