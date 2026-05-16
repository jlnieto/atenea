package com.atenea.api.mobile;

public record MobileUploadTelemetryResponse(
        long backendTotalMs,
        long backendEnsureDirectoryMs,
        long backendCopyMs,
        long backendPermissionsMs,
        long backendMetadataMs
) {
}
