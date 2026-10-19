package com.atenea.api.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.service.mobile.MobilePushNotificationService;
import com.atenea.service.mobile.MobileNotificationInboxService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/notifications")
public class MobileNotificationController {

    private final MobilePushNotificationService mobilePushNotificationService;
    private final MobileNotificationInboxService inboxService;

    public MobileNotificationController(MobilePushNotificationService mobilePushNotificationService,
                                        MobileNotificationInboxService inboxService) {
        this.mobilePushNotificationService = mobilePushNotificationService;
        this.inboxService = inboxService;
    }

    @GetMapping
    public MobileNotificationInboxService.Inbox inbox(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestParam(defaultValue = "0") int page) {
        return inboxService.list(operator.operatorId(), page);
    }

    @PostMapping("/{id}/read")
    public MobileNotificationInboxService.Item markRead(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable UUID id) {
        return inboxService.markRead(operator.operatorId(), id);
    }

    @PostMapping("/{eventId}/opened")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markOpened(@AuthenticationPrincipal AuthenticatedOperator operator,
                           @PathVariable UUID eventId) {
        inboxService.markOpened(operator.operatorId(), eventId);
    }

    @GetMapping("/push-devices")
    public List<MobilePushDeviceResponse> getPushDevices(
            @AuthenticationPrincipal AuthenticatedOperator operator
    ) {
        return mobilePushNotificationService.getDevices(operator);
    }

    @PostMapping("/push-token")
    @ResponseStatus(HttpStatus.CREATED)
    public MobilePushDeviceResponse registerPushToken(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody RegisterPushTokenRequest request
    ) {
        return mobilePushNotificationService.registerPushToken(operator, request);
    }

    @PostMapping("/push-token/unregister")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregisterPushToken(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @Valid @RequestBody UnregisterPushTokenRequest request
    ) {
        mobilePushNotificationService.unregisterPushToken(operator, request);
    }
}
