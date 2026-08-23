package com.atenea.auth.session;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record SessionInventoryProjection(
        UUID familyId,
        String clientType,
        String deviceLabel,
        Instant createdAt,
        Instant lastUsedAt,
        Instant absoluteExpiresAt,
        SessionInventoryState state,
        boolean current
) {

    private static final Pattern CLIENT_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,23}$");
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cntrl}]");

    public SessionInventoryProjection {
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(clientType, "clientType");
        Objects.requireNonNull(deviceLabel, "deviceLabel");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        Objects.requireNonNull(state, "state");
        if (!CLIENT_TYPE.matcher(clientType).matches()) {
            throw new IllegalArgumentException("Invalid clientType");
        }
        if (deviceLabel.isBlank()
                || deviceLabel.length() > 120
                || !deviceLabel.equals(deviceLabel.trim())
                || CONTROL_CHARACTER.matcher(deviceLabel).find()) {
            throw new IllegalArgumentException("Invalid deviceLabel");
        }
        if (lastUsedAt.isBefore(createdAt)
                || absoluteExpiresAt.isBefore(lastUsedAt)
                || !absoluteExpiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Invalid session timestamps");
        }
        if (!isRoundedToMinute(createdAt)
                || !isRoundedToMinute(lastUsedAt)
                || !isRoundedToMinute(absoluteExpiresAt)) {
            throw new IllegalArgumentException("Session timestamps must be rounded to minutes");
        }
    }

    public static Instant roundToMinute(Instant value) {
        return Objects.requireNonNull(value, "value").truncatedTo(ChronoUnit.MINUTES);
    }

    private static boolean isRoundedToMinute(Instant value) {
        return value.equals(roundToMinute(value));
    }
}
