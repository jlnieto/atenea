package com.atenea.service.operations;

import com.atenea.api.operations.ManagedHostResponse;
import com.atenea.mobilepush.MobilePushDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OperationsMonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(OperationsMonitoringScheduler.class);

    private final OperationsService operationsService;
    private final MobilePushDispatchService mobilePushDispatchService;

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

    int runCheckCycle() {
        int degradedHosts = 0;
        for (ManagedHostResponse host : operationsService.listHosts()) {
            try {
                OperationsMonitoringResult result = operationsService.checkApacheAndWebsites(host.id());
                if (!result.degraded()) {
                    continue;
                }
                mobilePushDispatchService.notifyOperationsDegradation(
                        result.hostId(), result.hostName(), result.errors());
                degradedHosts++;
            } catch (RuntimeException exception) {
                log.warn("Scheduled operations check failed hostId={}: {}", host.id(), exception.getMessage());
            }
        }
        return degradedHosts;
    }
}
