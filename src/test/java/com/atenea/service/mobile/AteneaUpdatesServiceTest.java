package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.DeliveryRejectedException;
import com.atenea.persistence.auth.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AteneaUpdatesServiceTest {
    final OperatorRepository operators=mock(OperatorRepository.class);
    final AteneaUpdatesSnapshotStore snapshots=mock(AteneaUpdatesSnapshotStore.class);
    final AteneaUpdatesSynchronizer sync=mock(AteneaUpdatesSynchronizer.class);
    final AteneaUpdatesService service=new AteneaUpdatesService(operators,snapshots,sync);
    final AuthenticatedOperator actor=new AuthenticatedOperator(5L,"synthetic@test.invalid","Test");
    @BeforeEach void active(){var o=new OperatorEntity();o.setActive(true);when(operators.findById(5L)).thenReturn(Optional.of(o));}
    static AteneaUpdatesService.Updates payload(Instant at){return new AteneaUpdatesService.Updates("atenea-updates/v1",at,"CURRENT",at,null,null,null,true,List.of(),null,List.of());}
    @Test void readUsesOnlyAPersistedSnapshotAndPreservesItsEvidenceTimestamp(){
        var value=payload(Instant.now().minusSeconds(60));when(snapshots.find(1)).thenReturn(new AteneaUpdatesSnapshotStore.Snapshot(value,Instant.now(),null));
        var result=service.read(actor,1);assertEquals(value.observedAt(),result.observedAt());assertEquals("READY",result.refreshState());
        verify(sync).request(1);verify(snapshots).find(1);verifyNoMoreInteractions(sync,snapshots);
    }
    @Test void coldReadReturnsImmediatelyAsPendingWithoutInventingCurrentEvidence(){
        var result=service.read(actor,1);assertEquals("PENDING",result.refreshState());assertEquals("UNAVAILABLE",result.historyState());assertNull(result.backend());
    }
    @Test void staleAndFailedRefreshRetainReadableContentButAreExplicitlyCached(){
        var value=payload(Instant.now().minusSeconds(400));when(snapshots.find(1)).thenReturn(new AteneaUpdatesSnapshotStore.Snapshot(value,Instant.now(),null));
        assertEquals("REFRESHING",service.read(actor,1).refreshState());
        when(snapshots.find(1)).thenReturn(new AteneaUpdatesSnapshotStore.Snapshot(value,Instant.now(),"NEWS_REFRESH_UNAVAILABLE"));
        var result=service.read(actor,1);assertEquals("CACHED",result.historyState());assertEquals("UNAVAILABLE",result.refreshState());assertEquals(value.observedAt(),result.observedAt());
    }
    @Test void inactiveOrMissingActorAndOutOfBoundsPagesCannotReadOrRequestRefresh(){
        assertThrows(DeliveryRejectedException.class,()->service.read(null,1));
        for(int page:List.of(0,51,Integer.MAX_VALUE))assertThrows(DeliveryRejectedException.class,()->service.read(actor,page));
        when(operators.findById(5L)).thenReturn(Optional.empty());assertThrows(DeliveryRejectedException.class,()->service.read(actor,1));
        verifyNoInteractions(snapshots,sync);
    }
}
