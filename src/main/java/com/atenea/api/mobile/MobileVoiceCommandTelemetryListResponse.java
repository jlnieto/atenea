package com.atenea.api.mobile;

import java.util.List;

public record MobileVoiceCommandTelemetryListResponse(
        List<MobileVoiceCommandTelemetryResponse> items
) {
}
