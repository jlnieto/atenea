package com.atenea.service.mobile;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ValidationRecoveryReconciliationTest {
    private final ValidationRecoveryStore store=mock(ValidationRecoveryStore.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final ValidationRecoveryService service=spy(new ValidationRecoveryService(
        store,null,null,null,null,null,null,null,null,null,null,null,manager,null));
    private final UUID id=UUID.randomUUID();

    private void failedPoll(String currentState) {
        when(manager.getTransaction(any())).thenAnswer(ignored->new SimpleTransactionStatus());
        when(store.pending()).thenReturn(List.of(id));
        when(store.queuedInvestigations()).thenReturn(List.of());
        when(store.get(id,true)).thenReturn(new ValidationRecoveryStore.Entry(id,1L,2L,
            UUID.randomUUID(),"REPAIR",currentState,1,"4".repeat(64),null,null,null,null,null,Instant.now()));
        doThrow(new IllegalStateException("Synthetic lost response")).when(service).process(id);
        service.reconcile();
    }

    @ParameterizedTest
    @ValueSource(strings={"PREPARE_REQUESTED","PREPARING","APPLY_REQUESTED","APPLYING"})
    void failedPollLocksAdmissionBeforeTheRowAndAttentionUpdate(String state) {
        failedPoll(state);
        var order=inOrder(store);
        order.verify(store).admissionLock();
        order.verify(store).get(id,true);
        order.verify(store).state(id,"ATTENTION","VALIDATION_REPAIR_NEEDS_INSPECTION");
        verify(manager).commit(any());
    }

    @ParameterizedTest
    @ValueSource(strings={"READY","APPLIED","CANCELLED","ATTENTION"})
    void lateFailureCannotOverwriteAnotherReconcilerResult(String state) {
        failedPoll(state);
        verify(store).admissionLock();
        verify(store).get(id,true);
        verify(store,never()).state(any(),anyString(),any());
    }
}
