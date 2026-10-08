package com.atenea.service.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.operations.ManagedHostResponse;
import com.atenea.mobilepush.MobilePushDispatchService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class OperationsMonitoringSchedulerTest {

    @Mock
    private OperationsService operationsService;

    @Mock
    private MobilePushDispatchService mobilePushDispatchService;

    private OperationsMonitoringScheduler scheduler;
    private ManagedHostResponse host;

    @BeforeEach
    void setUp() {
        scheduler = new OperationsMonitoringScheduler(operationsService, mobilePushDispatchService);
        host = new ManagedHostResponse(3L, "dedicado-principal", "Servidor dedicado", "prod", true);
    }

    @Test
    void healthyCycleDoesNotSendPush() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        when(operationsService.checkApacheAndWebsites(3L))
                .thenReturn(new OperationsMonitoringResult(3L, host.name(), List.of()));

        assertEquals(0, scheduler.runCheckCycle());

        verify(mobilePushDispatchService, never())
                .notifyOperationsDegradation(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void degradedCycleSendsConcreteErrors() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        List<String> errors = List.of(
                "Apache: systemctl is-active apache2 = inactive",
                "Web Cliente (https://cliente.test): DEGRADED - Slow response 3200ms above 2500ms threshold");
        when(operationsService.checkApacheAndWebsites(3L))
                .thenReturn(new OperationsMonitoringResult(3L, host.name(), errors));

        assertEquals(1, scheduler.runCheckCycle());

        verify(mobilePushDispatchService).notifyOperationsDegradation(3L, host.name(), errors);
    }

    @Test
    void twoConsecutiveDegradedCyclesSendTwoPushes() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        List<String> errors = List.of("Apache: código de salida 1");
        when(operationsService.checkApacheAndWebsites(3L))
                .thenReturn(new OperationsMonitoringResult(3L, host.name(), errors));

        scheduler.runCheckCycle();
        scheduler.runCheckCycle();

        verify(mobilePushDispatchService, times(2))
                .notifyOperationsDegradation(3L, host.name(), errors);
    }

    @Test
    void scheduledCheckDefaultsToFiveMinutesAndRemainsConfigurable() throws Exception {
        Method method = OperationsMonitoringScheduler.class.getMethod("checkEveryFiveMinutes");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("${ATENEA_OPERATIONS_MONITORING_INITIAL_DELAY_MS:300000}",
                scheduled.initialDelayString());
        assertEquals("${ATENEA_OPERATIONS_MONITORING_DELAY_MS:300000}", scheduled.fixedRateString());
    }
}
