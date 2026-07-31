package com.atenea.mobilepush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FcmPushSenderTest {

    private FcmPushSender sender;

    @BeforeEach
    void setUp() {
        sender = new FcmPushSender(mock(HttpClient.class), new ObjectMapper(), new MobilePushProperties());
    }

    @Test
    void unregisteredProviderCodeIsClosedInvalidTokenDiagnostic() {
        FcmDeliveryException failure = sender.deliveryFailure(404, """
                {"error":{"details":[{"errorCode":"UNREGISTERED"}],"message":"synthetic-token-value"}}
                """);

        assertEquals(FcmDeliveryException.FailureKind.INVALID_TOKEN, failure.failureKind());
        assertEquals("FCM_TOKEN_INVALID", failure.diagnosticCode());
        assertFalse(failure.getMessage().contains("synthetic-token-value"));
    }

    @Test
    void providerThrottleAndServerFailureAreRetryable() {
        assertEquals(FcmDeliveryException.FailureKind.RETRYABLE,
                sender.deliveryFailure(429, "quota").failureKind());
        assertEquals(FcmDeliveryException.FailureKind.RETRYABLE,
                sender.deliveryFailure(503, "unavailable").failureKind());
    }

    @Test
    void closedClientRejectionIsPermanentWithoutProviderContent() {
        FcmDeliveryException failure = sender.deliveryFailure(403, "credential detail");

        assertEquals(FcmDeliveryException.FailureKind.PERMANENT, failure.failureKind());
        assertEquals("FCM_PROVIDER_REJECTED", failure.diagnosticCode());
        assertFalse(failure.getMessage().contains("credential detail"));
    }

    @Test
    void authenticationThrottleRetriesButCredentialRejectionDoesNot() {
        assertEquals(FcmDeliveryException.FailureKind.RETRYABLE,
                sender.authenticationFailure(429).failureKind());
        assertEquals("FCM_AUTH_RETRYABLE", sender.authenticationFailure(503).diagnosticCode());
        assertEquals(FcmDeliveryException.FailureKind.PERMANENT,
                sender.authenticationFailure(401).failureKind());
    }

    @Test
    @SuppressWarnings("unchecked")
    void genericEventUsesItsImmutableIdentityAsAndroidReplacementTag() {
        String eventId = "adad1f5a-18b8-43d2-8e9c-c5f92037f693";
        Map<String, Object> payload = sender.payload(new FcmPushSender.FcmPushMessage(
                "synthetic-token",
                "Tarea completada",
                "Abre Atenea para continuar esta sesión",
                Map.of("notificationEventId", eventId, "sessionId", 12L)));

        Map<String, Object> message = (Map<String, Object>) payload.get("message");
        Map<String, Object> android = (Map<String, Object>) message.get("android");
        Map<String, Object> notification = (Map<String, Object>) android.get("notification");
        assertEquals(eventId, notification.get("tag"));
        assertEquals(eventId, ((Map<String, String>) message.get("data")).get("notificationEventId"));

        Map<String, Object> legacy = sender.payload(new FcmPushSender.FcmPushMessage(
                "synthetic-token", "Legacy", "Legacy body", Map.of("type", "PR_MERGED")));
        Map<String, Object> legacyMessage = (Map<String, Object>) legacy.get("message");
        Map<String, Object> legacyAndroid = (Map<String, Object>) legacyMessage.get("android");
        assertFalse(((Map<String, Object>) legacyAndroid.get("notification")).containsKey("tag"));
        assertTrue(((Map<String, String>) legacyMessage.get("data")).containsKey("type"));
    }
}
