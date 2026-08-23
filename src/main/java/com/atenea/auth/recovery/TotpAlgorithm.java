package com.atenea.auth.recovery;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class TotpAlgorithm {
    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30L;
    private static final int WINDOW = 1;

    private TotpAlgorithm() {
    }

    public static OptionalLong matchingCounter(byte[] secret, String presented, Instant now) {
        boolean canonical = presented != null && presented.matches("^[0-9]{6}$");
        byte[] candidate = (canonical ? presented : "000000").getBytes(StandardCharsets.US_ASCII);
        long current = Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
        long matched = -1L;
        for (long counter = current - WINDOW; counter <= current + WINDOW; counter++) {
            byte[] expected = code(secret, counter).getBytes(StandardCharsets.US_ASCII);
            if (java.security.MessageDigest.isEqual(expected, candidate)) {
                matched = Math.max(matched, counter);
            }
        }
        return canonical && matched >= 0 ? OptionalLong.of(matched) : OptionalLong.empty();
    }

    public static String code(byte[] secret, long counter) {
        if (secret == null || secret.length < 16 || counter < 0) {
            throw new IllegalArgumentException("Invalid TOTP material");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int value = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", value % 1_000_000);
        } catch (Exception exception) {
            throw new IllegalStateException("TOTP algorithm unavailable", exception);
        }
    }
}
