package com.atenea.api.mobile;

import java.time.Instant;

public record MobileUploadResponse(
        String originalFilename,
        String storedFilename,
        String contentType,
        long sizeBytes,
        String storedPath,
        String latestMetadataPath,
        Instant uploadedAt,
        MobileUploadTelemetryResponse telemetry
) {
}
