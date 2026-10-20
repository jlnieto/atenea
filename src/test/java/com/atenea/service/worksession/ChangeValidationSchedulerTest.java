package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class ChangeValidationSchedulerTest {
    @Test void pendingIntentsAreDrivenWithoutAnyMobileRequestAndFailuresAreIsolated() {
        var store=mock(ChangeValidationWorkflowStore.class);var coordinator=mock(ChangeValidationCoordinator.class);
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();when(store.pending()).thenReturn(List.of(first,second));
        doThrow(new IllegalStateException("synthetic transaction unavailable")).when(coordinator).progress(first);
        assertDoesNotThrow(()->new ChangeValidationScheduler(store,coordinator).progress());
        verify(coordinator).progress(first);verify(coordinator).progress(second);verifyNoMoreInteractions(coordinator);
    }
    @Test void orchestrationUsesDedicatedSchedulerRatherThanNewsOrMobileLifecycle() throws Exception {
        var annotation=ChangeValidationScheduler.class.getMethod("progress").getAnnotation(Scheduled.class);
        assertEquals("changeValidationTaskScheduler",annotation.scheduler());assertEquals(3000,annotation.fixedDelay());
    }
}
