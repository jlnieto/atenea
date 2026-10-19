package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class AteneaUpdatesSynchronizerTest {
    final AteneaUpdatesLoader loader=mock(AteneaUpdatesLoader.class);
    final AteneaUpdatesSnapshotStore snapshots=mock(AteneaUpdatesSnapshotStore.class);
    final AteneaUpdatesSynchronizer sync=new AteneaUpdatesSynchronizer(loader,snapshots);
    @Test void coldStartWarmsOneBoundedPageOutsideHttp(){
        var value=AteneaUpdatesServiceTest.payload(Instant.now());when(loader.observe(1)).thenReturn(value);
        sync.request(2);verifyNoInteractions(loader,snapshots);sync.synchronize();verify(loader).observe(1);verify(snapshots).save(1,value);verify(loader,never()).observe(2);
    }
    @Test void failurePreservesTheLastPayloadAndHasABoundedRetryDelay(){
        var value=AteneaUpdatesServiceTest.payload(Instant.now());when(snapshots.find(1)).thenReturn(new AteneaUpdatesSnapshotStore.Snapshot(value,Instant.now(),"NEWS_REFRESH_UNAVAILABLE"));
        sync.synchronize();verifyNoInteractions(loader);verify(snapshots,never()).save(anyInt(),any());
        when(snapshots.find(1)).thenReturn(new AteneaUpdatesSnapshotStore.Snapshot(value,Instant.now().minusSeconds(31),"NEWS_REFRESH_UNAVAILABLE"));
        when(loader.observe(1)).thenThrow(new IllegalStateException("synthetic unavailable"));sync.synchronize();verify(snapshots).failed(1);verify(snapshots,never()).save(anyInt(),any());
    }
    @Test void schedulingAndPageInterestAreClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,()->sync.request(51));assertThrows(IllegalArgumentException.class,()->sync.request(0));
        assertEquals("productNewsScheduler",AteneaUpdatesSynchronizer.class.getMethod("synchronize").getAnnotation(Scheduled.class).scheduler());
    }
}
