package com.atenea.service.operations;

import com.atenea.api.operations.ManagedHostResponse;
import com.atenea.mobilepush.MobilePushDispatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OperationsMonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(OperationsMonitoringScheduler.class);
    private static final int ALERT_CONFIRMATION_CYCLES = 3;

    private final OperationsService operationsService;
    private final MobilePushDispatchService mobilePushDispatchService;
    private final Map<Long, HostNotificationState> notificationStates = new HashMap<>();

    public OperationsMonitoringScheduler(
            OperationsService operationsService,
            MobilePushDispatchService mobilePushDispatchService
    ) {
        this.operationsService = operationsService;
        this.mobilePushDispatchService = mobilePushDispatchService;
    }

    @Scheduled(
            initialDelayString = "${ATENEA_OPERATIONS_MONITORING_INITIAL_DELAY_MS:300000}",
            fixedRateString = "${ATENEA_OPERATIONS_MONITORING_DELAY_MS:300000}")
    public void checkEveryFiveMinutes() {
        runCheckCycle();
    }

    synchronized int runCheckCycle() {
        int degradedHosts = 0;
        List<ManagedHostResponse> hosts = operationsService.listHosts();
        notificationStates.keySet().retainAll(hosts.stream().map(ManagedHostResponse::id).toList());
        for (ManagedHostResponse host : hosts) {
            try {
                OperationsMonitoringResult result = operationsService.checkApacheAndWebsites(host.id());
                if (!result.degraded()) {
                    notificationStates.remove(host.id());
                    continue;
                }
                degradedHosts++;
                HostNotificationState state = notificationStates.computeIfAbsent(
                        host.id(), ignored -> new HostNotificationState());
                List<OperationsMonitoringIssue> newAlerts = state.newAlerts(result.issues());
                if (!newAlerts.isEmpty()) {
                    List<String> messages = newAlerts.stream().map(OperationsMonitoringIssue::message).toList();
                    if (newAlerts.stream().allMatch(issue -> "ssh".equals(issue.key()))) {
                        mobilePushDispatchService.notifyOperationsVerificationFailure(
                                result.hostId(), result.hostName(), messages);
                    } else {
                        mobilePushDispatchService.notifyOperationsDegradation(
                                result.hostId(), result.hostName(), messages);
                    }
                }
            } catch (RuntimeException exception) {
                // An unobserved cycle cannot confirm an issue or keep an alert active.
                notificationStates.remove(host.id());
                log.warn("Scheduled operations check failed hostId={}: {}", host.id(), exception.getMessage());
            }
        }
        return degradedHosts;
    }

    private static final class HostNotificationState {
        private final Map<String, Integer> consecutiveObservations = new HashMap<>();
        private final Set<String> notifiedIssues = new HashSet<>();

        List<OperationsMonitoringIssue> newAlerts(List<OperationsMonitoringIssue> issues) {
            Set<String> activeKeys = new HashSet<>();
            for (OperationsMonitoringIssue issue : issues) {
                activeKeys.add(issue.key());
            }
            consecutiveObservations.keySet().retainAll(activeKeys);
            notifiedIssues.retainAll(activeKeys);

            List<OperationsMonitoringIssue> alerts = new ArrayList<>();
            for (OperationsMonitoringIssue issue : issues) {
                int observations = consecutiveObservations.compute(issue.key(),
                        (key, previous) -> Math.min(ALERT_CONFIRMATION_CYCLES,
                                previous == null ? 1 : previous + 1));
                if ((!issue.requiresConfirmation() || observations >= ALERT_CONFIRMATION_CYCLES)
                        && notifiedIssues.add(issue.key())) {
                    alerts.add(issue);
                }
            }
            return alerts;
        }
    }
}
