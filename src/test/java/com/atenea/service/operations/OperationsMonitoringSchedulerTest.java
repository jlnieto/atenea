package com.atenea.service.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.atenea.api.operations.ManagedHostResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class OperationsMonitoringSchedulerTest {
    final OperationsService operations=mock(OperationsService.class);
    final OperationsMonitoringNotifications notifications=mock(OperationsMonitoringNotifications.class);
    final OperationsMonitoringScheduler scheduler=new OperationsMonitoringScheduler(operations,notifications);
    final ManagedHostResponse host=new ManagedHostResponse(3L,"dedicado","Servidor","prod",true);

    @Test void healthyCycleIsRecordedSoARealRecoveryResetsDurableCounters() {
        var result=new OperationsMonitoringResult(3L,host.name(),List.of(),41L);
        when(operations.listHosts()).thenReturn(List.of(host));when(operations.checkApacheAndWebsites(3L)).thenReturn(result);
        assertEquals(0,scheduler.runCheckCycle());verify(notifications).record(result);verifyNoMoreInteractions(notifications);
    }
    @Test void twoDegradedChecksAreBothRecordedWithoutIncidentDeduplication() {
        var first=new OperationsMonitoringResult(3L,host.name(),List.of(new OperationsMonitoringIssue("apache","Apache: inactive",false)),41L);
        var second=new OperationsMonitoringResult(3L,host.name(),first.issues(),42L);
        when(operations.listHosts()).thenReturn(List.of(host));when(operations.checkApacheAndWebsites(3L)).thenReturn(first,second);
        assertEquals(1,scheduler.runCheckCycle());assertEquals(1,scheduler.runCheckCycle());
        verify(notifications).record(first);verify(notifications).record(second);
    }
    @Test void unavailableCheckIsNotAFakeRecoveryAndDoesNotBlockOtherHosts() {
        var other=new ManagedHostResponse(4L,"otro","Servidor","prod",true);
        var healthy=new OperationsMonitoringResult(4L,"otro",List.of(),42L);
        when(operations.listHosts()).thenReturn(List.of(host,other));
        when(operations.checkApacheAndWebsites(3L)).thenThrow(new IllegalStateException("synthetic"));
        when(operations.checkApacheAndWebsites(4L)).thenReturn(healthy);
        scheduler.runCheckCycle();verify(notifications).unobserved(3L);verify(notifications).record(healthy);
    }
    @Test void scheduledCheckDefaultsToFiveMinutesOnItsOwnScheduler() throws Exception {
        var scheduled=OperationsMonitoringScheduler.class.getMethod("checkEveryFiveMinutes").getAnnotation(Scheduled.class);
        assertEquals("${ATENEA_OPERATIONS_MONITORING_INITIAL_DELAY_MS:300000}",scheduled.initialDelayString());
        assertEquals("${ATENEA_OPERATIONS_MONITORING_DELAY_MS:300000}",scheduled.fixedRateString());
        assertEquals("operationsProbeScheduler",scheduled.scheduler());
    }
}
