package com.atenea.service.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.*;
import com.atenea.github.*;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.remoteworker.RemoteWorkerClient;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Global product news. Every operation here is read-only, never a delivery intent. */
@Service
public class AteneaUpdatesService {
    private static final GitHubRepositoryRef APP=new GitHubRepositoryRef("jlnieto","atenea");
    private static final GitHubRepositoryRef PLATFORM=new GitHubRepositoryRef("jlnieto","atenea-remote-worker-spec");
    private final GitHubClient github;
    private final ReleaseControlClient release;
    private final OperatorRepository operators;
    private final RemoteWorkerClient worker;
    private final Map<Integer,CachedPage> pages=new LinkedHashMap<>();
    private final Map<String,String> inclusions=new LinkedHashMap<>();
    public AteneaUpdatesService(GitHubClient github,ReleaseControlClient release,OperatorRepository operators,RemoteWorkerClient worker) {
        this.github=github;this.release=release;this.operators=operators;this.worker=worker;
    }
    public Updates read(AuthenticatedOperator actor,int page) {
        if (actor==null || actor.operatorId()==null || operators.findById(actor.operatorId()).filter(o -> o.isActive()).isEmpty()) {
            throw new DeliveryRejectedException("AUTHENTICATED_OPERATOR_REQUIRED");
        }
        if (page<1 || page>50) throw new DeliveryRejectedException("CLOSED_REQUEST_REQUIRED");
        ReleaseControlClient.AppObservation app=null;ReleaseControlClient.AndroidObservation apk=null;ReleaseControlClient.PlatformObservation platform=null;
        List<String> unavailable=new ArrayList<>();
        try { app=ReleaseControlClient.verifyAppObservation(release.observeApp()); } catch (RuntimeException e) { unavailable.add("BACKEND_OBSERVATION_UNAVAILABLE"); }
        try { apk=ReleaseControlClient.verifyAndroidObservation(release.observeAndroid()); } catch (RuntimeException e) { unavailable.add("ANDROID_OBSERVATION_UNAVAILABLE"); }
        try { platform=ReleaseControlClient.verifyPlatformObservation(release.observePlatform()); } catch (RuntimeException e) { unavailable.add("PLATFORM_OBSERVATION_UNAVAILABLE"); }
        Boolean healthy=null;
        try { var health=worker.health();healthy=health.healthy() && "ax42-01".equals(health.workerId()); }
        catch (RuntimeException e) { unavailable.add("WORKER_HEALTH_UNAVAILABLE"); }
        CachedPage history;String historyState="CURRENT";
        synchronized (pages) {
            history=pages.get(page);
            if (history==null || history.at().isBefore(Instant.now().minusSeconds(120))) {
                try {
                    List<Historical> entries=new ArrayList<>();boolean more=false;
                    for (var repository:List.of(APP,PLATFORM)) {
                        var result=github.recentMergedChanges(repository,page);more|=result.more();
                        for (var change:result.changes()) {
                            List<DeliveryTarget> targets;
                            try { targets=repository.equals(PLATFORM) ? List.of(DeliveryTarget.AX42_PLATFORM)
                                    : ChangeDeliveryReadiness.classify(github.changedFiles(repository,change.number(),change.branch(),change.headCommit())); }
                            catch (RuntimeException unavailableDiff) { targets=null; }
                            entries.add(new Historical(repository,change,targets));
                        }
                    }
                    entries.sort(Comparator.comparing((Historical h) -> h.change().mergedAt()).reversed());
                    history=new CachedPage(Instant.now(),List.copyOf(entries),more && page<50);pages.put(page,history);
                } catch (RuntimeException e) { historyState=history==null ? "UNAVAILABLE" : "CACHED";unavailable.add("HISTORY_UNAVAILABLE"); }
            }
        }
        List<Entry> entries=new ArrayList<>();
        if (history!=null) for (var item:history.entries()) {
            Map<String,String> destinations=new LinkedHashMap<>();
            if (item.targets()!=null) for (var target:item.targets()) {
                String source=switch(target) { case APP_PROD -> app==null ? null : app.sourceCommit();case ANDROID_STABLE -> apk==null ? null : apk.sourceCommit();case AX42_PLATFORM -> platform==null ? null : platform.sourceCommit(); };
                String state=included(item,source);
                if ("INCLUDED".equals(state)) state=target==DeliveryTarget.APP_PROD && !app.healthy()
                        || target==DeliveryTarget.AX42_PLATFORM && !Boolean.TRUE.equals(healthy) ? "PUBLISHED_NEEDS_ATTENTION" : "PUBLISHED";
                destinations.put(target.name(),state);
            }
            var change=item.change();entries.add(new Entry((item.repository().equals(APP) ? "app:" : "platform:")+change.number(),
                    change.title(),change.summary(),change.mergedAt(),item.repository().equals(APP) ? "APP" : "PLATFORM",
                    item.targets(),destinations,change.url(),change.mergeCommit()));
        }
        return new Updates("atenea-updates/v1",Instant.now(),historyState,history==null ? null : history.at(),
                app,apk,platform,healthy,List.copyOf(entries),history!=null && history.more() ? page+1 : null,List.copyOf(unavailable));
    }
    private String included(Historical item,String source) {
        if (source==null) return "UNAVAILABLE";
        String key=item.repository()+"|"+item.change().number()+"|"+item.change().headCommit()+"|"+item.change().mergeCommit()+"|"+source;
        synchronized(inclusions) {
            if (inclusions.containsKey(key)) return inclusions.get(key);
            String status;
            try { var c=item.change();github.requireIntegratedChangeInSource(item.repository(),c.number(),c.branch(),c.headCommit(),c.mergeCommit(),source);status="INCLUDED"; }
            catch (GitHubIntegrationException rejected) { if (!"RELEASE_CHANGE_NOT_INCLUDED".equals(rejected.getMessage())) return "UNAVAILABLE";status="NOT_INCLUDED"; }
            if (inclusions.size()>=512) inclusions.remove(inclusions.keySet().iterator().next());inclusions.put(key,status);return status;
        }
    }
    private record Historical(GitHubRepositoryRef repository,GitHubClient.MergedChange change,List<DeliveryTarget> targets) { }
    private record CachedPage(Instant at,List<Historical> entries,boolean more) { }
    public record Entry(String id,String title,String summary,Instant integratedAt,String component,List<DeliveryTarget> requiredTargets,
            Map<String,String> publications,String pullRequestUrl,String mergeCommit) { }
    public record Updates(String protocolVersion,Instant observedAt,String historyState,Instant historyObservedAt,
            ReleaseControlClient.AppObservation backend,ReleaseControlClient.AndroidObservation android,
            ReleaseControlClient.PlatformObservation platform,Boolean workerHealthy,List<Entry> entries,Integer nextPage,List<String> unavailable) { }
}
