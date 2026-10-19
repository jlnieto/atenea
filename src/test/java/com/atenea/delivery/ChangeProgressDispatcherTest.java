package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.atenea.mobilepush.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChangeProgressDispatcherTest {
    final ChangeProgressDeliveryStore store=mock(ChangeProgressDeliveryStore.class);
    final FcmPushSender sender=mock(FcmPushSender.class);
    final UUID id=UUID.randomUUID();
    final ChangeProgressDeliveryStore.Command command=new ChangeProgressDeliveryStore.Command(id,UUID.randomUUID(),UUID.randomUUID(),21L,
            "synthetic-token","Preparación terminada","Falta confirmar",Instant.now().plusSeconds(600));
    @Test void disabledTransportLeavesDurableOutboxUntouched() {
        new ChangeProgressDispatcher(store,sender).dispatch();verifyNoInteractions(store);
    }
    @Test void sendUsesFixedProtocolStableEventOriginalExpiryAndNoFakeRun() {
        when(sender.isReady()).thenReturn(true);when(store.pending()).thenReturn(List.of(id));when(store.claim(id)).thenReturn(command);
        new ChangeProgressDispatcher(store,sender).dispatch();
        var captor=org.mockito.ArgumentCaptor.forClass(List.class);verify(sender).send(captor.capture());
        var message=(FcmPushSender.FcmPushMessage)captor.getValue().getFirst();
        assertEquals(command.event().toString(),message.data().get("notificationEventId"));
        assertEquals("atenea://work-sessions/21/conversation",message.data().get("deepLink"));
        assertFalse(message.data().containsKey("runId"));assertEquals(command.expiresAt(),message.expiresAt());
        verify(store).accepted(command);verify(store,never()).failed(any(),any());
    }
    @Test void ambiguousFailureRetainsSameEventForRetryWithoutAcknowledgingSuccess() {
        when(sender.isReady()).thenReturn(true);when(store.pending()).thenReturn(List.of(id));when(store.claim(id)).thenReturn(command);
        doThrow(new RuntimeException("synthetic")).when(sender).send(any());
        new ChangeProgressDispatcher(store,sender).dispatch();
        verify(store).failed(eq(command),argThat(f->f.failureKind()==FcmDeliveryException.FailureKind.RETRYABLE));
        verify(store,never()).accepted(any());
    }
    @Test void merelyCreatingPrIsNotReadyForIntegrationAndOtherMilestonesHaveHonestCopy() {
        var op=new DeliveryOperation(id,21L,7L,"PUBLISH_PR",DeliveryTarget.APP_PROD,null,UUID.randomUUID(),"WAITING_CI",null,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),null,Instant.now(),Instant.now());
        assertNull(ChangeProgressNotifications.copy(op,"SUCCEEDED"));
        assertTrue(ChangeProgressNotifications.copy(op,"INTEGRATION_READY").body().contains("Todavía no se ha publicado"));
        assertTrue(ChangeProgressNotifications.copy(op,"BLOCKED").title().contains("atención"));
    }
}
