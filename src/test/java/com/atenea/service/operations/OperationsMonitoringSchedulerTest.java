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
        String apache = "Apache: systemctl is-active apache2 = inactive";
        String slow = "Web Cliente (https://cliente.test): DEGRADED - Slow response 3200ms above 2500ms threshold";
        when(operationsService.checkApacheAndWebsites(3L))
                .thenReturn(result(issue("apache", apache, false), issue("website:30:DEGRADED", slow, true)));

        assertEquals(1, scheduler.runCheckCycle());

        verify(mobilePushDispatchService).notifyOperationsDegradation(3L, host.name(), List.of(apache));
    }

    @Test
    void sustainedSlowResponseSendsOnePushAfterThreeCycles() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        String slow = "Web Cliente: DEGRADED - Slow response";
        when(operationsService.checkApacheAndWebsites(3L))
                .thenReturn(result(issue("website:30:DEGRADED", slow, true)));

        scheduler.runCheckCycle();
        scheduler.runCheckCycle();
        verify(mobilePushDispatchService, never()).notifyOperationsDegradation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        scheduler.runCheckCycle();
        scheduler.runCheckCycle();

        verify(mobilePushDispatchService, times(1))
                .notifyOperationsDegradation(3L, host.name(), List.of(slow));
    }

    @Test
    void ongoingApacheFailureSendsOnlyOnePush() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        when(operationsService.checkApacheAndWebsites(3L))
                .thenReturn(result(issue("apache", "Apache: inactive", false)));

        scheduler.runCheckCycle();
        scheduler.runCheckCycle();
        scheduler.runCheckCycle();

        verify(mobilePushDispatchService, times(1))
                .notifyOperationsDegradation(3L, host.name(), List.of("Apache: inactive"));
    }

    @Test
    void intermittentSlowResponseDoesNotSendPush() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        when(operationsService.checkApacheAndWebsites(3L)).thenReturn(
                result(issue("website:30:DEGRADED", "slow", true)), result(),
                result(issue("website:30:DEGRADED", "slow", true)), result());

        for (int i = 0; i < 4; i++) {
            scheduler.runCheckCycle();
        }

        verify(mobilePushDispatchService, never()).notifyOperationsDegradation(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void changingLatencyTextDoesNotCreateAnotherAlertForSameWebsite() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        when(operationsService.checkApacheAndWebsites(3L)).thenReturn(
                result(issue("website:30:DEGRADED", "slow 3100ms", true)),
                result(issue("website:30:DEGRADED", "slow 3400ms", true)),
                result(issue("website:30:DEGRADED", "slow 3200ms", true)),
                result(issue("website:30:DEGRADED", "slow 3600ms", true)));

        for (int i = 0; i < 4; i++) {
            scheduler.runCheckCycle();
        }

        verify(mobilePushDispatchService).notifyOperationsDegradation(3L, host.name(), List.of("slow 3200ms"));
    }

    @Test
    void differentSlowWebsitesNeedTheirOwnConfirmation() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        when(operationsService.checkApacheAndWebsites(3L)).thenReturn(
                result(issue("website:30:DEGRADED", "web A", true)),
                result(issue("website:30:DEGRADED", "web A", true),
                        issue("website:31:DEGRADED", "web B", true)),
                result(issue("website:31:DEGRADED", "web B", true)),
                result(issue("website:31:DEGRADED", "web B", true)));

        for (int i = 0; i < 4; i++) {
            scheduler.runCheckCycle();
        }

        verify(mobilePushDispatchService).notifyOperationsDegradation(3L, host.name(), List.of("web B"));
    }

    @Test
    void recoveredIssueCanAlertAgainAndNewDownSiteAlertsImmediately() {
        when(operationsService.listHosts()).thenReturn(List.of(host));
        when(operationsService.checkApacheAndWebsites(3L)).thenReturn(
                result(issue("website:30:DEGRADED", "slow", true)),
                result(issue("website:30:DEGRADED", "slow", true)),
                result(issue("website:30:DEGRADED", "slow", true)),
                result(issue("website:30:DEGRADED", "slow", true),
                        issue("website:31:DOWN", "down", false)),
                result(),
                result(issue("website:31:DOWN", "down", false)));

        for (int i = 0; i < 6; i++) {
            scheduler.runCheckCycle();
        }

        verify(mobilePushDispatchService).notifyOperationsDegradation(3L, host.name(), List.of("slow"));
        verify(mobilePushDispatchService, times(2)).notifyOperationsDegradation(3L, host.name(), List.of("down"));
    }

    private OperationsMonitoringIssue issue(String key, String message, boolean confirmSlowResponse) {
        return new OperationsMonitoringIssue(key, message, confirmSlowResponse);
    }

    private OperationsMonitoringResult result(OperationsMonitoringIssue... issues) {
        return new OperationsMonitoringResult(3L, host.name(), List.of(issues));
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
