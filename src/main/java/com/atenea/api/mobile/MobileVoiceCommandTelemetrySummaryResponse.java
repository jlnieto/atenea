package com.atenea.api.mobile;

import java.util.List;

public record MobileVoiceCommandTelemetrySummaryResponse(
        List<MobileVoiceCommandTelemetrySummaryItemResponse> items
) {
}
