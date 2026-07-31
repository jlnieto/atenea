package com.atenea.service.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.codexoperations.CodexSessionOperationsProperties;
import com.atenea.mobilepush.FcmPushSender;
import com.atenea.persistence.notification.NotificationDeliveryRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryDispatcherTest {

    @Mock private NotificationDeliveryRepository deliveryRepository;
    @Mock private NotificationDeliveryClaimService claimService;
    @Mock private FcmPushSender fcmPushSender;

    private CodexSessionOperationsProperties properties;
    private NotificationDeliveryDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        properties = new CodexSessionOperationsProperties();
        dispatcher = new NotificationDeliveryDispatcher(
                properties, deliveryRepository, claimService, fcmPushSender);
    }

    @Test
    void gateOffDoesNotReadOrSendDeliveries() {
        dispatcher.dispatchPending();

        verify(deliveryRepository, never()).findPendingIds(any(Pageable.class));
        verify(fcmPushSender, never()).send(any());
    }

    @Test
    void configuredDispatcherSendsOnlyClaimedPersistentDelivery() {
        properties.setNotificationOutboxEnabled(true);
        when(fcmPushSender.isReady()).thenReturn(true);
        when(deliveryRepository.findPendingIds(any(Pageable.class))).thenReturn(List.of(41L));
        NotificationDeliveryCommand command = new NotificationDeliveryCommand(
                41L, "synthetic-token", "Tarea completada", "Abre Atenea",
                Map.of("type", "RUN_COMPLETED", "sessionId", 12L, "runId", 55L));
        when(claimService.claim(41L)).thenReturn(command);

        dispatcher.dispatchPending();

        verify(fcmPushSender).send(any());
        verify(claimService).delivered(41L);
        verify(claimService, never()).failed(41L);
    }

    @Test
    void providerFailureIsPersistedWithoutLoggingTokenOrThrowing() {
        properties.setNotificationOutboxEnabled(true);
        when(fcmPushSender.isReady()).thenReturn(true);
        when(deliveryRepository.findPendingIds(any(Pageable.class))).thenReturn(List.of(42L));
        NotificationDeliveryCommand command = new NotificationDeliveryCommand(
                42L, "synthetic-token", "Tarea completada", "Abre Atenea",
                Map.of("type", "RUN_COMPLETED"));
        when(claimService.claim(42L)).thenReturn(command);
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic provider failure"))
                .when(fcmPushSender).send(any());

        dispatcher.dispatchPending();

        verify(claimService).failed(42L);
        verify(claimService, never()).delivered(42L);
    }
}
