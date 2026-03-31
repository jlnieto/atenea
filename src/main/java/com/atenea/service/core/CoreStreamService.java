package com.atenea.service.core;

import com.atenea.api.core.CoreCommandEventsResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
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
public class CoreStreamService {

    private static final long EMITTER_TIMEOUT_MS = 0L;
    private static final long POLL_INTERVAL_MS = 1000L;

    private final CoreCommandEventService coreCommandEventService;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<SseEmitter, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public CoreStreamService(CoreCommandEventService coreCommandEventService) {
        this.coreCommandEventService = coreCommandEventService;
        this.scheduler = Executors.newScheduledThreadPool(2, daemonThreadFactory());
    }

    public SseEmitter streamCommandEvents(Long commandId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        CoreCommandEventsResponse initial = coreCommandEventService.getEvents(commandId);
        send(emitter, "core-command-events", initial);

        final Integer[] previousSize = new Integer[]{initial.events().size()};
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                CoreCommandEventsResponse current = coreCommandEventService.getEvents(commandId);
                if (!Objects.equals(previousSize[0], current.events().size())) {
                    send(emitter, "core-command-events", current);
                    previousSize[0] = current.events().size();
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
            Thread thread = new Thread(runnable, "core-stream");
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
