package com.atenea.delivery;

import com.atenea.mobilepush.FcmPushSender;
import com.atenea.mobilepush.FcmDeliveryException;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Retries retain the same event/tag; ACCEPTED is transport acceptance, not device receipt. */
@Service
public class ChangeProgressDispatcher {
    private final ChangeProgressDeliveryStore store;
    private final FcmPushSender sender;
    public ChangeProgressDispatcher(ChangeProgressDeliveryStore store,FcmPushSender sender) { this.store=store; this.sender=sender; }
    @Scheduled(scheduler="changeProgressPushScheduler",fixedDelayString="${atenea.change-progress.push-poll-ms:5000}")
    public void dispatch() {
        if(!sender.isReady()) return;
        for(var id:store.pending()) {
            var command=store.claim(id);
            if(command==null) continue;
            try {
                sender.send(List.of(new FcmPushSender.FcmPushMessage(command.token(),command.title(),command.body(),Map.of(
                        "schemaVersion","atenea-change-progress-v1","eventType","CHANGE_PROGRESS","type","CHANGE_PROGRESS",
                        "notificationEventId",command.event().toString(),"sessionId",command.session(),
                        "deepLinkKind","WORK_SESSION_CONVERSATION","deepLink","atenea://work-sessions/"+command.session()+"/conversation"),command.expiresAt())));
                store.accepted(command);
            } catch(FcmDeliveryException failure) { store.failed(command,failure); }
            catch(RuntimeException failure) { store.failed(command,new FcmDeliveryException(
                    FcmDeliveryException.FailureKind.RETRYABLE,"FCM_TRANSPORT_ERROR",failure)); }
        }
    }
}
