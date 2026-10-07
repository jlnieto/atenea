package com.atenea.api.mobile;

import com.atenea.persistence.mobile.MobileDiagnosticReportRepository;
import java.time.Instant;
import java.util.UUID;

public record MobileDiagnosticReceiptResponse(UUID id, Instant receivedAt, Instant generatedAt,
        String appVersionName, int appVersionCode, String deviceModel, int sizeBytes,
        String sha256, String contentPath) {
    public static MobileDiagnosticReceiptResponse from(MobileDiagnosticReportRepository.Receipt receipt) {
        return new MobileDiagnosticReceiptResponse(receipt.getId(), receipt.getReceivedAt(), receipt.getGeneratedAt(),
                receipt.getAppVersionName(), receipt.getAppVersionCode(), receipt.getDeviceModel(),
                receipt.getSizeBytes(), receipt.getSha256(), "/api/mobile/diagnostics/" + receipt.getId() + "/content");
    }
}
