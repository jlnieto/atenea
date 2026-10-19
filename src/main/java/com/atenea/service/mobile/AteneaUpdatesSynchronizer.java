package com.atenea.service.mobile;

import java.time.Instant;
import java.util.concurrent.ConcurrentSkipListSet;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AteneaUpdatesSynchronizer {
    private final AteneaUpdatesLoader loader;private final AteneaUpdatesSnapshotStore snapshots;
    private final ConcurrentSkipListSet<Integer> requested=new ConcurrentSkipListSet<>();
    public AteneaUpdatesSynchronizer(AteneaUpdatesLoader loader,AteneaUpdatesSnapshotStore snapshots){this.loader=loader;this.snapshots=snapshots;requested.add(1);}
    public void request(int page){if(page<1 || page>50)throw new IllegalArgumentException("Closed page");requested.add(page);}
    @Scheduled(initialDelayString="${atenea.news.initial-delay-ms:5000}",fixedDelayString="${atenea.news.poll-ms:2000}",scheduler="productNewsScheduler")
    public void synchronize() {
        for(int page:requested) {
            var saved=snapshots.find(page);
            if(saved!=null && saved.attemptedAt().isAfter(Instant.now().minusSeconds(saved.errorCode()==null ? 120 : 30)))continue;
            try{var value=loader.observe(page);
                if(!"CURRENT".equals(value.historyState()))throw new IllegalStateException("History not current");
                snapshots.save(page,value);
            }catch(RuntimeException unavailable){snapshots.failed(page);}
            break; // A bounded page; never on a request or notification scheduler.
        }
    }
}
