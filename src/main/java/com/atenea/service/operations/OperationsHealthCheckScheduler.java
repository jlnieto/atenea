package com.atenea.service.operations;

import com.atenea.api.operations.ManagedHostResponse;
import com.atenea.api.operations.OperationsActionRunResponse;
import com.atenea.api.operations.OperationsHostStatusResponse;
import com.atenea.api.operations.WebsiteCheckResponse;
import com.atenea.mobilepush.MobilePushDispatchService;
import com.atenea.persistence.operations.OperationsActionRunStatus;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OperationsHealthCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(OperationsHealthCheckScheduler.class);

    private final OperationsService operationsService;
    private final MobilePushDispatchService mobilePushDispatchService;

    public OperationsHealthCheckScheduler(
            OperationsService operationsService,
            MobilePushDispatchService mobilePushDispatchService
    ) {
        this.operationsService = operationsService;
        this.mobilePushDispatchService = mobilePushDispatchService;
    }

    @Scheduled(fixedDelayString = "${ATENEA_OPERATIONS_HEALTH_CHECK_DELAY_MS:300000}")
    public void checkPeriodically() {
        for (ManagedHostResponse host : operationsService.listHosts()) {
            try {
                notifyIfDegraded(operationsService.getHostStatus(host.id()));
            } catch (RuntimeException exception) {
                log.warn("Periodic operations health check failed for hostId={}: {}", host.id(), exception.getMessage());
            }
        }
    }

    private void notifyIfDegraded(OperationsHostStatusResponse status) {
        List<String> errors = new ArrayList<>();
        OperationsActionRunResponse hostCheck = status.hostStatusRun();
        if (hostCheck != null && hostCheck.status() == OperationsActionRunStatus.FAILED) {
            errors.add("Comprobación remota: " + firstNonBlank(
                    hostCheck.report() == null ? null : hostCheck.report().summary(),
                    hostCheck.stderrSummary(),
                    hostCheck.stdoutSummary(),
                    "estado FAILED"));
        }
        status.websiteChecks().stream()
                .filter(check -> !check.healthy())
                .map(this::websiteError)
                .forEach(errors::add);
        if (!errors.isEmpty()) {
            mobilePushDispatchService.notifyOperationsDegraded(
                    status.host().id(),
                    status.host().name(),
                    errors);
        }
    }

    private String websiteError(WebsiteCheckResponse check) {
        return check.name() + ": " + firstNonBlank(
                check.error(),
                check.state() + " (HTTP " + (check.statusCode() == null ? "sin respuesta" : check.statusCode())
                        + ", " + check.durationMillis() + "ms)");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "sin detalle";
    }
}
