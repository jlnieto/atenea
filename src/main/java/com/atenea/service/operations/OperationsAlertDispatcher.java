package com.atenea.service.operations;

import com.atenea.mobilepush.*;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Reuses FCM and its typed failures. A replay keeps the same notification identity. */
@Service
public class OperationsAlertDispatcher {
    private static final Logger log=LoggerFactory.getLogger(OperationsAlertDispatcher.class);
    private final OperationsAlertDeliveryStore store;private final FcmPushSender sender;
    public OperationsAlertDispatcher(OperationsAlertDeliveryStore store,FcmPushSender sender){this.store=store;this.sender=sender;}
    @Scheduled(fixedDelayString="${atenea.operations-push.poll-ms:5000}",scheduler="operationsPushScheduler")
    public void dispatch() {
        if(!sender.isReady())return;
        for(var id:store.pending()) {
            var command=store.claim(id);if(command==null)continue;
            try {
                sender.send(List.of(new FcmPushSender.FcmPushMessage(command.pushToken(),command.title(),command.body(),Map.of(
                    "schemaVersion","atenea-operations-notification-v1","eventType","OPERATIONS_ALERT","type",command.kind(),
                    "notificationEventId",command.eventId().toString(),"hostId",command.hostId(),"deepLinkKind","OPERATIONS_HOST",
                    "deepLink","atenea://operations/"+command.hostId()))));
                store.delivered(command);
            }catch(FcmDeliveryException failure){store.failed(command,failure);log.warn("Operations push retained deliveryId={} code={}",id,failure.diagnosticCode());}
            catch(RuntimeException failure){store.failed(command,new FcmDeliveryException(FcmDeliveryException.FailureKind.RETRYABLE,"FCM_TRANSPORT_ERROR",failure));log.warn("Operations push retained deliveryId={}",id);}
        }
    }
}
