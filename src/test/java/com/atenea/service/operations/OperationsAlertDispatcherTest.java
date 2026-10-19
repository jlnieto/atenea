package com.atenea.service.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.atenea.mobilepush.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OperationsAlertDispatcherTest {
    final OperationsAlertDeliveryStore store=mock(OperationsAlertDeliveryStore.class);
    final FcmPushSender sender=mock(FcmPushSender.class);
    final OperationsAlertDispatcher dispatcher=new OperationsAlertDispatcher(store,sender);
    OperationsAlertDeliveryStore.Command command(){return new OperationsAlertDeliveryStore.Command(UUID.randomUUID(),1,UUID.randomUUID(),3L,"OPERATIONS_DEGRADATION","Degradación detectada","Apache: inactive; HTTP 503",5L,"synthetic-token",java.time.Instant.now().plusSeconds(600));}
    @Test void unconfiguredProviderCannotClaimOrAcknowledgeADelivery(){dispatcher.dispatch();verifyNoInteractions(store);verify(sender,never()).send(anyList());}
    @Test void acceptedDeliveryKeepsItsEventIdentityForAndroidAndAcknowledgesOnlyThatDevice(){
        var c=command();when(sender.isReady()).thenReturn(true);when(store.pending()).thenReturn(List.of(c.id()));when(store.claim(c.id())).thenReturn(c);
        doAnswer(call->{List<FcmPushSender.FcmPushMessage> messages=call.getArgument(0);var m=messages.getFirst();
            assertEquals("synthetic-token",m.to());assertEquals("Apache: inactive; HTTP 503",m.body());
            assertEquals(c.eventId().toString(),m.data().get("notificationEventId"));assertEquals("atenea://operations/3",m.data().get("deepLink"));
            assertEquals(c.expiresAt(),m.expiresAt());return null;}).when(sender).send(anyList());
        dispatcher.dispatch();verify(store).delivered(c);verify(store,never()).failed(any(),any());
    }
    @Test void typedFailureIsRetainedAndDoesNotBlockAnotherRegisteredDevice(){
        var a=command();var b=command();when(sender.isReady()).thenReturn(true);when(store.pending()).thenReturn(List.of(a.id(),b.id()));when(store.claim(a.id())).thenReturn(a);when(store.claim(b.id())).thenReturn(b);
        var failure=new FcmDeliveryException(FcmDeliveryException.FailureKind.RETRYABLE,"FCM_TRANSPORT_ERROR",null);
        doThrow(failure).doNothing().when(sender).send(anyList());dispatcher.dispatch();
        verify(store).failed(a,failure);verify(store,never()).delivered(a);verify(store).delivered(b);
    }
}
