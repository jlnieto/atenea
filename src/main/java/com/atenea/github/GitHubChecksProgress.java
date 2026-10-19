package com.atenea.github;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

/** Bounded read model. Exact-head observations are information, never integration authority. */
@Component
public class GitHubChecksProgress {
    public record Check(String label, String state, Long startedAt, Long finishedAt) { }
    public record Evidence(String sourceCommit, String state, long observedAt, String serviceState,
            List<Check> checks) { }
    private record Cached(long expires, Evidence evidence) { }
    private final GitHubClient github;
    private final GitHubServiceHealth health;
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    public GitHubChecksProgress(GitHubClient github, GitHubServiceHealth health) { this.github=github; this.health=health; }

    public synchronized Evidence observe(String source, String branch, boolean build, boolean hasPr) {
        if (source == null || !source.matches("[0-9a-f]{40}") || branch == null || !(build && branch.equals("main")
                || !build && branch.matches("atenea/(?:change|android-version)-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
            throw new GitHubIntegrationException("PROGRESS_IDENTITY_INVALID");
        long now=Instant.now().getEpochSecond();
        String key=source+":"+branch+":"+build+":"+hasPr;
        var prior=cache.get(key);
        if (prior!=null && prior.expires()>now) return prior.evidence();
        Evidence evidence;
        try {
            var checks=new ArrayList<Check>();
            boolean[] closing={false};
            String workflow=build ? "release-artifacts-v1.yml" : "ufd-validation-v1.yml";
            JsonNode run=latest(workflow,source,branch,"push");
            if(run==null && !build && branch.startsWith("atenea/android-version-")) run=latest(workflow,source,branch,"create");
            if (run==null && !build) run=github.ownedHeadUfdRun(new GitHubRepositoryRef("jlnieto","atenea"),source,branch);
            append(run,build ? "Construcción de la versión" : "Validación del cambio",checks,closing);
            if (!build && hasPr) {
                String base=github.canonicalMain(new GitHubRepositoryRef("jlnieto","atenea"));
                if(base==null || !base.matches("[0-9a-f]{40}")) throw invalid();
                append(latest("backend-merge-validation-v1.yml",source,branch,"pull_request"),
                        "Backend combinado con main",checks,closing,base);
            }
            String state=checks.stream().anyMatch(c->c.state().equals("FAILED")) ? "FAILED"
                    : checks.stream().anyMatch(c->c.state().equals("UNAVAILABLE")) ? "UNAVAILABLE"
                    : checks.stream().anyMatch(c->c.state().equals("RUNNING")) ? "RUNNING"
                    : checks.stream().anyMatch(c->c.state().equals("QUEUED")) ? "QUEUED"
                    : checks.stream().anyMatch(c->c.state().equals("NOT_STARTED")) ? "NOT_STARTED"
                    : closing[0] ? "CLOSING"
                    : checks.stream().anyMatch(c->c.state().equals("PASSED")) ? "PASSED" : "UNAVAILABLE";
            evidence=new Evidence(source,state,now,health.observe(),List.copyOf(checks));
        } catch (RuntimeException unavailable) {
            // A failed refresh does not turn stale data into fresh success or a test failure.
            evidence=new Evidence(source,"UNAVAILABLE",prior==null ? now : prior.evidence().observedAt(),health.observe(),
                    prior==null ? List.of() : prior.evidence().checks());
        }
        if (cache.size()>=128) cache.remove(cache.keySet().iterator().next());
        cache.put(key,new Cached(now+30,evidence));
        return evidence;
    }

    private JsonNode latest(String workflow,String source,String branch,String event) {
        var values=github.buildApi("/repos/jlnieto/atenea/actions/workflows/"+workflow+"/runs?head_sha="+source
                +"&event="+event+"&per_page=100");
        if (!values.path("workflow_runs").isArray() || values.path("workflow_runs").size()>=100) throw invalid();
        JsonNode latest=null;
        for(var run:values.path("workflow_runs")) {
            if(source.equals(run.path("head_sha").asText()) && branch.equals(run.path("head_branch").asText())
                    && event.equals(run.path("event").asText()) && (".github/workflows/"+workflow).equals(run.path("path").asText())
                    && "jlnieto/atenea".equals(run.path("head_repository").path("full_name").asText())
                    && (latest==null || run.path("id").asLong()>latest.path("id").asLong())) latest=run;
        }
        return latest;
    }

    private void append(JsonNode run,String label,List<Check> result,boolean[] closing) {
        append(run,label,result,closing,null);
    }
    private void append(JsonNode run,String label,List<Check> result,boolean[] closing,String requiredBase) {
        if(run==null) { result.add(new Check(label,"NOT_STARTED",null,null)); return; }
        long id=run.path("id").asLong();
        if(id<1) throw invalid();
        var jobs=github.buildApi("/repos/jlnieto/atenea/actions/runs/"+id+"/jobs?filter=latest&per_page=100");
        if(!jobs.path("jobs").isArray() || jobs.path("total_count").asInt(-1)<0
                || jobs.path("total_count").asInt()>100 || jobs.path("total_count").asInt()!=jobs.path("jobs").size()) throw invalid();
        var relevant=new ArrayList<Check>();
        for(var job:jobs.path("jobs")) {
            if("skipped".equals(job.path("conclusion").asText())) continue;
            String name=job.path("name").asText();
            if(requiredBase!=null && !name.equals("backend-merge-"+requiredBase)) {
                result.add(new Check(label,"UNAVAILABLE",null,null));return;
            }
            String safeLabel=switch(name) {
                case "ufd-validation","ufd-owned-head" -> "Validación del cambio";
                case "backend" -> "Construcción y pruebas backend";
                case "android" -> "Construcción y pruebas Android";
                default -> name.matches("backend-merge-[0-9a-f]{40}") ? "Backend combinado con main" : null;
            };
            if(safeLabel==null) throw invalid(); // Never return arbitrary workflow text or incomplete counts.
            String state=job.path("status").asText().equals("completed")
                    ? job.path("conclusion").asText().equals("success") ? "PASSED" : "FAILED"
                    : job.path("status").asText().equals("in_progress") ? "RUNNING" : "QUEUED";
            relevant.add(new Check(safeLabel,state,time(job,"started_at"),time(job,"completed_at")));
        }
        boolean completed="completed".equals(run.path("status").asText());
        if(!completed && !relevant.isEmpty() && relevant.stream().allMatch(c->c.state().equals("PASSED"))) closing[0]=true;
        if(completed && !"success".equals(run.path("conclusion").asText()))
            relevant.add(new Check(label,"FAILED",time(run,"run_started_at"),time(run,"updated_at")));
        if(relevant.isEmpty()) relevant.add(new Check(label,completed ? "UNAVAILABLE" : "QUEUED",null,null));
        result.addAll(relevant);
    }
    private static Long time(JsonNode node,String key) {
        if(!node.path(key).isTextual()) return null;
        try { return Instant.parse(node.path(key).asText()).getEpochSecond(); }
        catch(RuntimeException invalid) { return null; }
    }
    private static GitHubIntegrationException invalid() { return new GitHubIntegrationException("PROGRESS_EVIDENCE_INCOMPLETE"); }
}
