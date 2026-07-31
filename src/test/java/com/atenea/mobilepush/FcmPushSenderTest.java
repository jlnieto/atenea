package com.atenea.mobilepush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
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
}
