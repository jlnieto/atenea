package com.atenea.service.mobile;

import com.atenea.api.mobile.MobileInboxResponse;
import com.atenea.api.mobile.MobileSessionEventResponse;
import com.atenea.api.mobile.MobileSessionEventsResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class MobileStreamService {

    private static final long EMITTER_TIMEOUT_MS = 0L;
    private static final long POLL_INTERVAL_MS = 1000L;

    private final MobileInboxService mobileInboxService;
    private final MobileSessionEventService mobileSessionEventService;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<SseEmitter, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public MobileStreamService(
            MobileInboxService mobileInboxService,
            MobileSessionEventService mobileSessionEventService
    ) {
        this.mobileInboxService = mobileInboxService;
        this.mobileSessionEventService = mobileSessionEventService;
        this.scheduler = Executors.newScheduledThreadPool(4, daemonThreadFactory());
    }

    public SseEmitter streamInbox() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        MobileInboxResponse initial = mobileInboxService.getInbox();
        send(emitter, "inbox", initial);

        final MobileInboxResponse[] previous = new MobileInboxResponse[]{initial};
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                MobileInboxResponse current = mobileInboxService.getInbox();
                if (!Objects.equals(previous[0], current)) {
                    send(emitter, "inbox", current);
                    previous[0] = current;
                } else {
                    send(emitter, "heartbeat", Instant.now().toString());
                }
            } catch (Exception exception) {
                emitter.completeWithError(exception);
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        registerLifecycle(emitter, task);
        return emitter;
    }

    public SseEmitter streamSessionEvents(Long sessionId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        MobileSessionEventsResponse initial = mobileSessionEventService.getEvents(sessionId, null, 200);
        send(emitter, "session-events", initial);

        final Set<String> sentEventIds = new HashSet<>();
        initial.events().stream()
                .map(MobileSessionEventResponse::eventId)
                .filter(Objects::nonNull)
                .forEach(sentEventIds::add);
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                MobileSessionEventsResponse current = mobileSessionEventService.getEvents(sessionId, null, 200);
                List<MobileSessionEventResponse> unseen = unseenEvents(current.events(), sentEventIds);
                if (!unseen.isEmpty()) {
                    send(emitter, "session-events", new MobileSessionEventsResponse(
                            sessionId, unseen, current.generatedAt()));
                } else {
                    send(emitter, "heartbeat", Instant.now().toString());
                }
            } catch (Exception exception) {
                emitter.completeWithError(exception);
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        registerLifecycle(emitter, task);
        return emitter;
    }

    static List<MobileSessionEventResponse> unseenEvents(
            List<MobileSessionEventResponse> events,
            Set<String> sentEventIds) {
        return events.stream()
                .filter(event -> event.eventId() == null || sentEventIds.add(event.eventId()))
                .toList();
    }

    private void registerLifecycle(SseEmitter emitter, ScheduledFuture<?> task) {
        tasks.put(emitter, task);
        emitter.onCompletion(() -> cancel(emitter));
        emitter.onTimeout(() -> cancel(emitter));
        emitter.onError(ignored -> cancel(emitter));
    }

    private void cancel(SseEmitter emitter) {
        ScheduledFuture<?> task = tasks.remove(emitter);
        if (task != null) {
            task.cancel(true);
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
    }

    private ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "mobile-stream");
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
