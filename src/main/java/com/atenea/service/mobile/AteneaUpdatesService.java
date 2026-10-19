package com.atenea.service.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.*;
import com.atenea.persistence.auth.OperatorRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Presentation snapshot only. Publication/admission never consumes this cache. */
@Service
public class AteneaUpdatesService {
    private final OperatorRepository operators;
    private final AteneaUpdatesSnapshotStore snapshots;
    private final AteneaUpdatesSynchronizer synchronizer;
    public AteneaUpdatesService(OperatorRepository operators,AteneaUpdatesSnapshotStore snapshots,AteneaUpdatesSynchronizer synchronizer) {
        this.operators=operators;this.snapshots=snapshots;this.synchronizer=synchronizer;
    }
    public Updates read(AuthenticatedOperator actor,int page) {
        if(actor==null || actor.operatorId()==null || operators.findById(actor.operatorId()).filter(o->o.isActive()).isEmpty())
            throw new DeliveryRejectedException("AUTHENTICATED_OPERATOR_REQUIRED");
        if(page<1 || page>50)throw new DeliveryRejectedException("CLOSED_REQUEST_REQUIRED");
        synchronizer.request(page); // Interest only; GET has no network or database writes.
        var saved=snapshots.find(page);
        if(saved==null || saved.payload()==null)return new Updates("atenea-updates/v1",Instant.now(),"UNAVAILABLE",null,
            null,null,null,null,List.of(),null,List.of("NEWS_SYNC_PENDING"),List.of(),"PENDING");
        var value=saved.payload();boolean stale=value.observedAt().isBefore(Instant.now().minusSeconds(180));
        String refresh=saved.errorCode()!=null ? "UNAVAILABLE" : stale ? "REFRESHING" : "READY";
        var unavailable=new ArrayList<>(value.unavailable());
        if(saved.errorCode()!=null && unavailable.size()<5)unavailable.add("NEWS_REFRESH_UNAVAILABLE");
        return new Updates(value.protocolVersion(),value.observedAt(),stale || saved.errorCode()!=null ? "CACHED" : value.historyState(),
            value.historyObservedAt(),value.backend(),value.android(),value.platform(),value.workerHealthy(),value.entries(),value.nextPage(),
            List.copyOf(unavailable),value.highlights(),refresh);
    }
    public record Entry(String id,String title,String summary,Instant integratedAt,String component,List<DeliveryTarget> requiredTargets,
            Map<String,String> publications,String pullRequestUrl,String mergeCommit) { }
    public record Updates(String protocolVersion,Instant observedAt,String historyState,Instant historyObservedAt,
            ReleaseControlClient.AppObservation backend,ReleaseControlClient.AndroidObservation android,
            ReleaseControlClient.PlatformObservation platform,Boolean workerHealthy,List<Entry> entries,Integer nextPage,List<String> unavailable,
            List<Entry> highlights,String refreshState) {
        public Updates(String protocolVersion,Instant observedAt,String historyState,Instant historyObservedAt,
            ReleaseControlClient.AppObservation backend,ReleaseControlClient.AndroidObservation android,
            ReleaseControlClient.PlatformObservation platform,Boolean workerHealthy,List<Entry> entries,Integer nextPage,List<String> unavailable) {
            this(protocolVersion,observedAt,historyState,historyObservedAt,backend,android,platform,workerHealthy,entries,nextPage,unavailable,List.of(),"READY");
        }
    }
}
