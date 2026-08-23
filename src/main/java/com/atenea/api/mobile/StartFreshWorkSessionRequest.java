package com.atenea.api.mobile;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record StartFreshWorkSessionRequest(@NotNull UUID idempotencyKey) {
}
