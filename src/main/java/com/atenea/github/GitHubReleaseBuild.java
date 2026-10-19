package com.atenea.github;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Read-only, fixed repository/workflow. No raw logs, signed download URLs or exception values leave this boundary. */
@Component
public class GitHubReleaseBuild {
    private final GitHubClient github;
    public GitHubReleaseBuild(GitHubClient github) { this.github=github; }
    public record Failure(String kind,String path,Integer line,String testClass,String testMethod) { }
    public record Evidence(String sourceCommit,String state,Long runId,Integer attempt,String url,
            String phase,String diagnosticSha256,List<Failure> failures) { }
    public Evidence observe(String source) {
        if(source==null || !source.matches("[0-9a-f]{40}")) throw invalid();
        var runs=github.buildApi("/repos/jlnieto/atenea/actions/workflows/release-artifacts-v1.yml/runs?head_sha="
            +source+"&branch=main&event=push&per_page=100");
        if(!runs.path("workflow_runs").isArray() || runs.path("workflow_runs").size()>=100) throw invalid();
        JsonNode latest=null;
        for(var run:runs.path("workflow_runs")) {
            if(source.equals(run.path("head_sha").asText()) && "main".equals(run.path("head_branch").asText())
                    && "push".equals(run.path("event").asText())
                    && ".github/workflows/release-artifacts-v1.yml".equals(run.path("path").asText())
                    && "jlnieto/atenea".equals(run.path("head_repository").path("full_name").asText())
                    && (latest==null || run.path("id").asLong()>latest.path("id").asLong())) latest=run;
        }
        if(latest==null) return new Evidence(source,"PENDING",null,null,null,"WAITING_CI",null,List.of());
        long id=latest.path("id").asLong();int attempt=latest.path("run_attempt").asInt();
        if(id<1 || attempt<1) throw invalid();
        String state=!"completed".equals(latest.path("status").asText()) ? "PENDING"
            : "success".equals(latest.path("conclusion").asText()) ? "READY" : "FAILED";
        String phase="PENDING".equals(state) ? "BUILDING" : "READY".equals(state) ? "COMPLETED" : "BUILD";
        var failures=new ArrayList<Failure>();String digest=null;
        if(state.equals("FAILED")) {
            var jobs=github.buildApi("/repos/jlnieto/atenea/actions/runs/"+id+"/jobs?filter=latest&per_page=100");
            if(!jobs.path("jobs").isArray() || jobs.path("total_count").asInt(-1)<0 || jobs.path("total_count").asInt()>100) throw invalid();
            for(var job:jobs.path("jobs")) {
                if(!"failure".equals(job.path("conclusion").asText())) continue;
                if("android".equals(job.path("name").asText())) phase="ANDROID_BUILD";
                else if("backend".equals(job.path("name").asText())) {
                    phase="BACKEND_BUILD";
                    for(var step:job.path("steps")) if("failure".equals(step.path("conclusion").asText())
                        && "Backend tests in the job-owned database".equals(step.path("name").asText())) phase="BACKEND_TEST_COMPILE_OR_TEST";
                }
            }
            var artifacts=github.buildApi("/repos/jlnieto/atenea/actions/runs/"+id+"/artifacts?per_page=100");
            if(!artifacts.path("artifacts").isArray() || artifacts.path("total_count").asInt(-1)<0
                    || artifacts.path("total_count").asInt()>100) throw invalid();
            JsonNode diagnostic=null;
            for(var artifact:artifacts.path("artifacts")) {
                if(!("atenea-build-diagnostic-app_prod-"+source+"-"+attempt).equals(artifact.path("name").asText()) || artifact.path("expired").asBoolean()) continue;
                if(diagnostic!=null || !artifact.path("digest").asText().matches("sha256:[0-9a-f]{64}")) throw invalid();
                diagnostic=artifact;
            }
            if(diagnostic!=null) {
                digest=diagnostic.path("digest").asText().substring(7);
                failures.addAll(parse(github.buildDiagnostic(diagnostic.path("id").asLong(),digest),source));
                if(failures.stream().anyMatch(f->f.kind().equals("COMPILATION"))) phase="BACKEND_COMPILATION";
            }
        }
        return new Evidence(source,state,id,attempt,"https://github.com/jlnieto/atenea/actions/runs/"+id,
            phase,digest,List.copyOf(failures));
    }
    static List<Failure> parse(JsonNode value,String source) {
        if(!fields(value,Set.of("protocol","sourceCommit","target","failures"))
                || !"atenea-build-diagnostic/v1".equals(value.path("protocol").asText())
                || !source.equals(value.path("sourceCommit").asText()) || !"APP_PROD".equals(value.path("target").asText())
                || !value.path("failures").isArray() || value.path("failures").size()>40) throw invalid();
        var result=new ArrayList<Failure>();
        for(var f:value.path("failures")) {
            if("COMPILATION".equals(f.path("kind").asText()) && fields(f,Set.of("kind","path","line"))
                    && f.path("path").asText().matches("src/(test|main)/java/([A-Za-z0-9_]+/)*[A-Za-z0-9_]+\\.java")
                    && f.path("line").isInt() && f.path("line").asInt()>0 && f.path("line").asInt()<1000000)
                result.add(new Failure("COMPILATION",f.path("path").asText(),f.path("line").asInt(),null,null));
            else if("TEST".equals(f.path("kind").asText()) && fields(f,Set.of("kind","testClass","testMethod"))
                    && f.path("testClass").asText().matches("[A-Za-z_$][A-Za-z0-9_$.]{0,240}")
                    && f.path("testMethod").asText().matches("[A-Za-z_$][A-Za-z0-9_$.]{0,240}"))
                result.add(new Failure("TEST",null,null,f.path("testClass").asText(),f.path("testMethod").asText()));
            else throw invalid();
        }
        return List.copyOf(result);
    }
    private static boolean fields(JsonNode value,Set<String> expected) {
        if(value==null || !value.isObject()) return false;
        var actual=new java.util.HashSet<String>();value.fieldNames().forEachRemaining(actual::add);return actual.equals(expected);
    }
    private static GitHubIntegrationException invalid() { return new GitHubIntegrationException("BUILD_EVIDENCE_INVALID"); }
}
