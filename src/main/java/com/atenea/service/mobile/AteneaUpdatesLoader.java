package com.atenea.service.mobile;

import com.atenea.service.mobile.AteneaUpdatesService.*;
import com.atenea.delivery.*;
import com.atenea.github.*;
import com.atenea.remoteworker.RemoteWorkerClient;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Global product news. Every operation here is read-only, never a delivery intent. */
@Service
public class AteneaUpdatesLoader {
    private static final GitHubRepositoryRef APP=new GitHubRepositoryRef("jlnieto","atenea");
    private static final GitHubRepositoryRef PLATFORM=new GitHubRepositoryRef("jlnieto","atenea-remote-worker-spec");
    private final GitHubClient github;
    private final ReleaseControlClient release;
    private final ProductNewsCopy copy;
    private final RemoteWorkerClient worker;
    private final Map<Integer,CachedPage> pages=new LinkedHashMap<>();
    private final Map<String,String> inclusions=new LinkedHashMap<>();
    public AteneaUpdatesLoader(GitHubClient github,ReleaseControlClient release,RemoteWorkerClient worker,ProductNewsCopy copy) {
        this.github=github;this.release=release;this.worker=worker;this.copy=copy;
    }
    public Updates observe(int page) {
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
        List<Historical> highlighted=new ArrayList<>();
        if(page==1) for(var note:copy.featured()) {
            try {
                var change=github.mergedChange(note.repository(),note.number());
                if(change!=null && note.mergeCommit().equals(change.mergeCommit()))
                    highlighted.add(new Historical(note.repository(),change,List.of(DeliveryTarget.APP_PROD)));
            } catch(RuntimeException missing) { /* Never invent a historical publication. */ }
        }
        List<Entry> entries=new ArrayList<>(),highlights=new ArrayList<>();
        List<Historical> all=new ArrayList<>();if(history!=null)all.addAll(history.entries());
        // Featured copy may already be on this page. Serialize each identity once.
        for(var item:highlighted)if(!all.contains(item))all.add(item);
        for (var item:all) {
            Map<String,String> destinations=new LinkedHashMap<>();
            if (item.targets()!=null) for (var target:item.targets()) {
                String source=switch(target) { case APP_PROD -> app==null ? null : app.sourceCommit();case ANDROID_STABLE -> apk==null ? null : apk.sourceCommit();case AX42_PLATFORM -> platform==null ? null : platform.sourceCommit(); };
                String state=included(item,source);
                if ("INCLUDED".equals(state)) state=target==DeliveryTarget.APP_PROD && !app.healthy()
                        || target==DeliveryTarget.AX42_PLATFORM && !Boolean.TRUE.equals(healthy) ? "PUBLISHED_NEEDS_ATTENTION" : "PUBLISHED";
                destinations.put(target.name(),state);
            }
            var change=item.change();var prose=copy.resolve(item.repository(),change);
            var entry=new Entry((item.repository().equals(APP) ? "app:" : "platform:")+change.number(),
                    prose.title(),prose.summary(),change.mergedAt(),item.repository().equals(APP) ? "APP" : "PLATFORM",
                    item.targets(),destinations,change.url(),change.mergeCommit());
            if(highlighted.contains(item))highlights.add(entry);else entries.add(entry);
        }
        return new Updates("atenea-updates/v1",Instant.now(),historyState,history==null ? null : history.at(),
                app,apk,platform,healthy,List.copyOf(entries),history!=null && history.more() ? page+1 : null,List.copyOf(unavailable),
                List.copyOf(highlights),"READY");
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
}
