package com.atenea.service.operations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OperationsMonitoringScheduler {
    private static final Logger log=LoggerFactory.getLogger(OperationsMonitoringScheduler.class);
    private final OperationsService operations;
    private final OperationsMonitoringNotifications notifications;
    public OperationsMonitoringScheduler(OperationsService operations,OperationsMonitoringNotifications notifications) {
        this.operations=operations;this.notifications=notifications;
    }
    @Scheduled(initialDelayString="${ATENEA_OPERATIONS_MONITORING_INITIAL_DELAY_MS:300000}",
        fixedRateString="${ATENEA_OPERATIONS_MONITORING_DELAY_MS:300000}",scheduler="operationsProbeScheduler")
    public void checkEveryFiveMinutes(){runCheckCycle();}
    synchronized int runCheckCycle() {
        int degraded=0;
        for(var host:operations.listHosts()) {
            try {
                var result=operations.checkApacheAndWebsites(host.id());
                notifications.record(result);
                if(result.degraded())degraded++;
            }catch(RuntimeException unavailable) {
                try { notifications.unobserved(host.id()); }
                catch(RuntimeException persistenceUnavailable) { log.warn("Monitoring state unavailable hostId={}",host.id()); }
                log.warn("Scheduled monitoring unavailable hostId={}",host.id());
            }
        }
        return degraded;
    }
}
