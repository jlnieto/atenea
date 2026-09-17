package com.atenea.service.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.operations.ManagedHostResponse;
import com.atenea.api.operations.OperationsActionRunResponse;
import com.atenea.api.operations.OperationsExecutionReportResponse;
import com.atenea.api.operations.OperationsHostStatusResponse;
import com.atenea.api.operations.WebsiteCheckResponse;
import com.atenea.mobilepush.MobilePushDispatchService;
import com.atenea.persistence.operations.OperationsActionRunStatus;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class OperationsHealthCheckSchedulerTest {

    @Mock
    private OperationsService operationsService;
    @Mock
    private MobilePushDispatchService mobilePushDispatchService;

    private OperationsHealthCheckScheduler scheduler;
    private ManagedHostResponse host;

    @BeforeEach
    void setUp() {
        scheduler = new OperationsHealthCheckScheduler(operationsService, mobilePushDispatchService);
        host = new ManagedHostResponse(3L, "dedicado-principal", "Servidor dedicado", "prod", true);
    }

    @Test
    void runsEveryFiveMinutesByDefault() throws Exception {
        Method method = OperationsHealthCheckScheduler.class.getMethod("checkPeriodically");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("${ATENEA_OPERATIONS_HEALTH_CHECK_DELAY_MS:300000}", scheduled.fixedDelayString());
    }

    @Test
    void healthyCheckDoesNotNotify() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        when(operationsService.getHostStatus(3L)).thenReturn(status(
                successfulRun("Servidor estable"),
                List.of(website("OK", true, null))));

        scheduler.checkPeriodically();

        verify(mobilePushDispatchService, never()).notifyOperationsDegraded(
                eq(3L), eq("dedicado-principal"), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void degradedCheckNotifiesWithConcreteErrors() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        when(operationsService.getHostStatus(3L)).thenReturn(status(
                failedRun("Apache no responde"),
                List.of(website(
                        "DEGRADED",
                        false,
                        "Slow response 3200ms above 2500ms threshold"))));

        scheduler.checkPeriodically();

        verify(mobilePushDispatchService).notifyOperationsDegraded(
                3L,
                "dedicado-principal",
                List.of(
                        "Comprobación remota: Apache no responde",
                        "Cliente: Slow response 3200ms above 2500ms threshold"));
    }

    @Test
    void consecutiveDegradedChecksNotifyEveryTime() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        OperationsHostStatusResponse degraded = status(
                successfulRun("Servidor estable"),
                List.of(website("DOWN", false, "Unexpected HTTP status 503")));
        when(operationsService.getHostStatus(3L)).thenReturn(degraded);

        scheduler.checkPeriodically();
        scheduler.checkPeriodically();

        verify(mobilePushDispatchService, times(2)).notifyOperationsDegraded(
                3L,
                "dedicado-principal",
                List.of("Cliente: Unexpected HTTP status 503"));
    }

    private OperationsHostStatusResponse status(
            OperationsActionRunResponse run,
            List<WebsiteCheckResponse> websiteChecks
    ) {
        return new OperationsHostStatusResponse(host, run, List.of(), websiteChecks, List.of());
    }

    private OperationsActionRunResponse successfulRun(String summary) {
        return run(OperationsActionRunStatus.SUCCEEDED, summary, null);
    }

    private OperationsActionRunResponse failedRun(String summary) {
        return run(OperationsActionRunStatus.FAILED, summary, "remote command failed");
    }

    private OperationsActionRunResponse run(
            OperationsActionRunStatus status,
            String summary,
            String stderr
    ) {
        return new OperationsActionRunResponse(
                41L,
                null,
                3L,
                null,
                "HOST_STATUS",
                status,
                status == OperationsActionRunStatus.SUCCEEDED ? 0 : 1,
                null,
                stderr,
                new OperationsExecutionReportResponse(
                        "HOST_STATUS", "dedicado-principal", status.name(), summary, List.of(), java.util.Map.of()),
                null,
                null);
    }

    private WebsiteCheckResponse website(String state, boolean healthy, String error) {
        return new WebsiteCheckResponse(
                30L,
                "Cliente",
                "https://cliente.test",
                200,
                healthy ? 200 : 503,
                healthy ? 120 : 3200,
                2500,
                10000,
                state,
                healthy,
                error);
    }
}
